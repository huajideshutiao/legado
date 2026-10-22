import Darwin
import UIKit
import UniformTypeIdentifiers

/// App Group 共享容器标识。
///
/// ⚠️ 必须与 `iosApp/iosApp.entitlements` / `ShareExtension/ShareExtension.entitlements`
/// 的 `com.apple.security.application-groups` 值、以及 Kotlin 侧
/// `ui/src/iosMain/.../IosShareInbox.kt` 的 `APP_GROUP_ID` 逐字一致 (改一处要四处同改)。
private let legadoAppGroupId = "group.shutiao.reader"

/// 容器内的收件目录名 —— Kotlin 侧 `IosShareInbox` 扫的就是它 (两边同一字面量)。
private let legadoInboxDirName = "ShareInbox"

/// 容器内的文本槽目录名 —— Kotlin 侧 `IosShareInbox.TEXT_DIR_NAME` 扫的是它 (两边同一字面量)。
///
/// 与 [legadoInboxDirName] 分开是因为语义不同: 收件目录里的东西会被宿主当成**文件**交给
/// "导入本地书籍"链, 分享进来的文本 (一条链接、一段引文) 必须走文本分流 (搜索 / 自动识别导入),
/// 落成 `.txt` 混进收件目录就等于把一段话当一本书导入。
private let legadoTextDirName = "ShareText"

/// 叫门 URL: 只负责把宿主带到前台, 载荷一律留在容器里。
///
/// scheme 见 `iosApp/Info.plist` 的 `CFBundleURLTypes` (`legado` / `yuedu`)。宿主侧
/// `LegadoDeepLink.parse` 对不带 `?src=` 的 URL 直接返回 null, 所以这条不会触发深链导入,
/// 只是把进程带起来 —— 带起来之后由 `IosShareInbox` 的取件钩子读文本槽。
private let legadoHostWakeURLString = "legado://share"

/// 收件目录拿不到的原因 (决定给用户看哪句说明)。
private enum InboxUnavailable: Error {
    /// 拿不到 App Group 容器 = 当前签名没有共享容器能力 (未签名 ipa / 签名未勾 App Groups)。
    case noSharedContainer
    /// 容器在但收件目录建不出来 (磁盘满等)。
    case cannotCreateInbox(String)

    /// 给用户的说明: 说清"这次分享没被接收"以及还能怎么导。
    var userMessage: String {
        switch self {
        case .noSharedContainer:
            return """
            当前安装包的签名不含共享容器（App Groups），无法把分享的内容交给 Legado。
            可换用带该能力的签名安装，或先把文件存到「文件」App，再用 Legado 的「导入本地书籍」导入；
            文本可先复制，再在 Legado 内搜索或粘贴地址导入。
            """
        case .cannotCreateInbox(let reason):
            return "无法创建接收目录：\(reason)"
        }
    }
}

/// 一次分享里解出来的两类内容。
private struct SharedContent {
    var files: [URL] = []
    var texts: [String] = []
}

/// 分享扩展的入口 ViewController (已接线)。
///
/// # 交付通道
/// 分享扩展**不能拉起宿主 App** —— Apple 现行文档把 `NSExtensionContext.open(_:)` 限定给
/// Today / iMessage 两类扩展点 ("In iOS, the Today and iMessage app extension points support
/// this method"), DTS 的口径是 "App extensions are not allowed to open URLs directly"。
/// 因此**唯一保证交付的通道**是 App Group: 扩展把内容写进共享容器, 宿主在启动 / 回到前台 /
/// 变成活动态时由 Kotlin 侧 `IosShareInbox` 取件。
/// - 文件 → `ShareInbox/` (宿主按文件交给导入链);
/// - 文本 (含网页链接) → `ShareText/` (宿主按文本走分流链)。
///
/// # 尽力把用户带进宿主 App (非保证行为, 拿不到就静默退回上一段)
/// 写完文本后再试 [wakeHost]: 沿 responder chain 找 `UIApplication` 调它的
/// `open(_:options:completionHandler:)` (业界实际做法, 与 Chrome iOS 的
/// `extension_open_url.mm`、Readest 分享扩展同一条路; 纯 Swift 写法见
/// https://gist.github.com/Tunous/363bb71baa5b401ff8a63304f6389be5, 标注 iOS 15–26 可用)。
/// 这条路**不是文档化契约**, 系统随时可能改成拒绝 (iOS 18 就把一参的 `openURL:` 掐成了
/// `Force returning false`), 所以它只当"锦上添花": 失败时内容仍在文本槽里, 用户切回本应用即取件,
/// 不丢件也不报错。
///
/// # 生效前提 (未签名产物上不生效)
/// App Group 靠 entitlement 落地, 而本仓 CI 产出的 ipa 是**未签名**的
/// (CODE_SIGNING_ALLOWED=NO, 与 iCloud 容器同样原因), 拿不到 entitlement →
/// `FileManager.containerURL(forSecurityApplicationGroupIdentifier:)` 恒返回 nil。
/// 此时**当场把原因摆给用户看** ([InboxUnavailable.noSharedContainer]): 用户在分享面板里
/// 点了本应用却什么都没发生, 至少在这里知道为什么、还能怎么导 —— 不静默丢件。
/// 签名 (侧载工具重签 / 正式签名) 且 App ID 勾上 App Groups 后才真正生效。
///
/// 完整背景见 `iosApp/project.yml` 的 `ShareExtension` 段。
///
/// # 为什么不用 SLComposeServiceViewController
/// 那个基类是"发帖/发消息"形态 (标题栏 + 预览 + 发送按钮), 与本应用"把内容交给自己"的语义不符;
/// 用裸 UIViewController 才能做成"接手即完成"的后台式交接
/// (只有收不下时才停下来跟用户说一声)。
final class ShareViewController: UIViewController {

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        loadAttachments { [weak self] content in
            guard let self else { return }
            guard !content.files.isEmpty || !content.texts.isEmpty else {
                self.finish(["没能从分享内容里取到文件或文本，未收到任何内容。"])
                return
            }
            self.deliver(content) { [weak self] failures in
                self?.finish(failures)
            }
        }
    }

    /// 交还分享面板: 全部收下就直接完成 (后台式交接, 不打断用户);
    /// 有没收下的先摆明哪件、为什么, 用户点"确定"后再完成。
    ///
    /// 收尾一律排到下一个主队列周期: 呈现对话框与交还控制权都不能在 viewDidAppear
    /// 的当前这一拍里做 (会撞上正在进行的呈现)。
    private func finish(_ failures: [String]) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard !failures.isEmpty else {
                self.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
                return
            }
            let alert = UIAlertController(
                title: "无法接收分享内容",
                message: failures.joined(separator: "\n\n"),
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "确定", style: .default) { [weak self] _ in
                self?.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            })
            self.present(alert, animated: true)
        }
    }

    /// 把分享内容拷/写进 App Group 容器 (文件进收件目录, 文本进文本槽)。
    ///
    /// 回调带回"用户能看懂的失败说明" (空数组 = 全部收下): 容器取不到、目录建不出来、
    /// 单件拷贝失败都会带出来, 由 [finish] 摆给用户看 —— 用户在这里点了本应用却什么都没发生时,
    /// 至少知道为什么。
    /// 单件落盘一律走"隐藏临时名 + 成功后原子改名": 宿主在拷贝期间取件只会看到完整件或没有件
    /// (宿主扫目录时跳过隐藏项), 同名覆盖也只在整份落完之后发生。
    ///
    /// 文本落成功后在**主队列**试一次 [wakeHost] (带前台是 best-effort, 见类型文档)。
    private func deliver(_ content: SharedContent, completion: @escaping ([String]) -> Void) {
        let container: URL
        do {
            container = try containerURL()
        } catch let unavailable as InboxUnavailable {
            NSLog("[Legado] share extension: container unavailable: %@", unavailable.userMessage)
            completion([unavailable.userMessage])
            return
        } catch {
            NSLog("[Legado] share extension: container unavailable: %@", error.localizedDescription)
            completion([InboxUnavailable.cannotCreateInbox(error.localizedDescription).userMessage])
            return
        }
        // 拷贝放后台队列: 分享进来的可能是上百 MB 的书/视频, 占着主队列做 IO 只会拖长
        // 扩展存活时间 (回调在拷贝完成后才 completeRequest, 不会被提前杀)
        DispatchQueue.global(qos: .userInitiated).async {
            var failures: [String] = []
            var textsDelivered = 0
            if !content.files.isEmpty {
                do {
                    failures += self.deliverFiles(content.files, to: try self.legadoDirectory(in: container, legadoInboxDirName))
                } catch {
                    failures.append(Self.unavailableMessage(of: error))
                }
            }
            if !content.texts.isEmpty {
                do {
                    let (count, errs) = try self.deliverTexts(content.texts, to: self.legadoDirectory(in: container, legadoTextDirName))
                    textsDelivered = count
                    failures += errs
                } catch {
                    failures.append(Self.unavailableMessage(of: error))
                }
            }
            DispatchQueue.main.async {
                if textsDelivered > 0 { self.wakeHost() }
                completion(failures)
            }
        }
    }

    /// 共享容器本体; 拿不到时抛 [InboxUnavailable.noSharedContainer]。
    private func containerURL() throws -> URL {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: legadoAppGroupId
        ) else { throw InboxUnavailable.noSharedContainer }
        return container
    }

    /// 容器内的一个 Legado 目录 (不存在时创建); 建不出来抛错, 由调用方转成用户可见说明。
    private func legadoDirectory(in container: URL, _ name: String) throws -> URL {
        let dir = container.appendingPathComponent(name, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        } catch {
            NSLog("[Legado] share extension: create %@ failed: %@", name, error.localizedDescription)
            throw InboxUnavailable.cannotCreateInbox(error.localizedDescription)
        }
        return dir
    }

    /// 目录建不出来的统一措辞 (容器在但子目录写不了)。
    private static func unavailableMessage(of error: Error) -> String {
        (error as? InboxUnavailable)?.userMessage
            ?? InboxUnavailable.cannotCreateInbox(error.localizedDescription).userMessage
    }

    /// 文件: 逐件从调用方容器拷进收件目录 (原件可能在别的 App 的沙盒里, 读前要起 security-scoped 授权)。
    private func deliverFiles(_ urls: [URL], to inbox: URL) -> [String] {
        var failures: [String] = []
        for url in urls {
            let name = url.lastPathComponent
            guard !name.isEmpty else {
                NSLog("[Legado] share extension: attachment without file name dropped")
                failures.append("有一件分享内容没有文件名，没能接收。")
                continue
            }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let dest = inbox.appendingPathComponent(name)
            let temp = tempFile(for: dest)
            do {
                // copyItem 要求目标不存在: 上一次被系统杀掉的拷贝会停在临时名上, 先清掉
                try? FileManager.default.removeItem(at: temp)
                try FileManager.default.copyItem(at: url, to: temp)
                try publish(temp: temp, to: dest)
                NSLog("[Legado] share extension delivered file: %@", name)
            } catch {
                NSLog("[Legado] share extension deliver failed (%@): %@", name, error.localizedDescription)
                failures.append("「\(name)」没能接收：\(error.localizedDescription)")
            }
        }
        return failures
    }

    /// 文本: 每条落一个 `.txt` (文件名带时间戳与序号, 多条分享不会互相覆盖)。
    private func deliverTexts(_ texts: [String], to slot: URL) -> (Int, [String]) {
        var failures: [String] = []
        var delivered = 0
        let stamp = Int(Date().timeIntervalSince1970 * 1000)
        for (index, text) in texts.enumerated() {
            let name = "share-\(stamp)-\(index).txt"
            let dest = slot.appendingPathComponent(name)
            let temp = tempFile(for: dest)
            do {
                try text.write(to: temp, atomically: false, encoding: .utf8)
                try publish(temp: temp, to: dest)
                delivered += 1
                NSLog("[Legado] share extension delivered text: %@", name)
            } catch {
                NSLog("[Legado] share extension deliver text failed (%@): %@", name, error.localizedDescription)
                failures.append("第 \(index + 1) 条文本没能接收：\(error.localizedDescription)")
            }
        }
        return (delivered, failures)
    }

    /// 隐藏临时件的名字 (`.<正式名>.part`): 与 Kotlin 侧 `IosShareInbox.TEMP_SUFFIX` 同一套契约,
    /// 宿主扫目录时跳过它, 因此半截内容不会被当成完整件取走。
    private func tempFile(for dest: URL) -> URL {
        let dir = dest.deletingLastPathComponent()
        return dir.appendingPathComponent(".\(dest.lastPathComponent).part")
    }

    /// 临时名 → 正式名的原子改名 (同目录 rename): 目标已存在时被整份替换。
    ///
    /// `Darwin.rename` 与宿主侧 `stage` 用的是同一个 POSIX 调用 —— Swift 里 `UIViewController`
    /// 经 `UIResponder` 在 iOS 16+ 也有一个 `rename(_:)` 实例方法, 裸写 `rename` 会解析成那个
    /// 成员, 所以必须带 `Darwin.` 限定。
    private func publish(temp: URL, to dest: URL) throws {
        defer { try? FileManager.default.removeItem(at: temp) }
        guard Darwin.rename(temp.path, dest.path) == 0 else {
            let code = errno
            throw NSError(
                domain: NSPOSIXErrorDomain,
                code: Int(code),
                userInfo: [NSLocalizedDescriptionKey: String(cString: strerror(code))]
            )
        }
    }

    /// 尽力把宿主带到前台: 沿 responder chain 找 `UIApplication` 调它未被禁的三参 `open`。
    ///
    /// 非文档化契约, 系统可能拒绝 (iOS 18 已把一参 `openURL:` 强制成 false), 所以这条**只负责
    /// 少一次手动切回**: 找不到、被拒绝、或 `legado://` 没注册, 都静默按"用户下次回本应用再取件"
    /// 处理 —— 内容已经在容器里, 不会丢。
    private func wakeHost() {
        guard let url = URL(string: legadoHostWakeURLString) else { return }
        var responder: UIResponder? = self
        while let current = responder {
            if let application = current as? UIApplication {
                application.open(url, options: [:], completionHandler: nil)
                return
            }
            responder = current.next
        }
        // 链路里找不到 UIApplication 时退回文档化入口: 它按 Apple 文档对分享扩展返回 false,
        // 试一次不多花的成本是零, 换来的是系统在将来的版本放开这条路时本应用自动受益。
        extensionContext?.open(url, completionHandler: nil)
    }

    /// 把 `extensionContext.inputItems` 里的附件解成文件与文本两类。
    ///
    /// 用 `loadItem` (而不是 `loadFileRepresentation`): 前者拿到的是系统给的原始 item,
    /// 对 `public.file-url` 就是文件 URL; 后者会把文件复制到临时目录并**要求调用方在
    /// 回调返回后立刻处理**, 多一层临时文件生命周期要管, 而交付本来就要整份拷进容器。
    ///
    /// 类型优先级 (一条附件只归一类, 避免同一份内容既当文件又当文本被处理两遍):
    /// `public.file-url` (文件) → `public.url` (浏览器分享的网页链接, 取 absoluteString 当文本)
    /// → `public.plain-text` / `public.text` (选中的文字、分享的描述)。
    private func loadAttachments(completion: @escaping (SharedContent) -> Void) {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else {
            completion(SharedContent())
            return
        }
        let providers = items.compactMap { $0.attachments }.flatMap { $0 }
        guard !providers.isEmpty else {
            completion(SharedContent())
            return
        }

        // 结果按 providers 下标回填, 保证顺序稳定 (并发 loadItem 的回调顺序不定)
        var collected = [SharedContent?](repeating: nil, count: providers.count)
        let group = DispatchGroup()
        for (index, provider) in providers.enumerated() {
            let kind = Self.attachmentKind(of: provider)
            guard let kind else { continue }
            group.enter()
            provider.loadItem(forTypeIdentifier: kind.identifier, options: nil) { item, error in
                defer { group.leave() }
                if let error {
                    NSLog("[Legado] share extension loadItem failed: %@", error.localizedDescription)
                    return
                }
                switch kind {
                case .fileURL:
                    if let url = Self.fileURL(from: item) {
                        var content = SharedContent()
                        content.files = [url]
                        collected[index] = content
                    }
                case .url:
                    if let text = Self.text(fromURLItem: item) {
                        var content = SharedContent()
                        content.texts = [text]
                        collected[index] = content
                    }
                case .text:
                    if let text = Self.text(fromTextItem: item) {
                        var content = SharedContent()
                        content.texts = [text]
                        collected[index] = content
                    }
                }
            }
        }
        group.notify(queue: .main) {
            var merged = SharedContent()
            for part in collected.compactMap({ $0 }) {
                merged.files.append(contentsOf: part.files)
                merged.texts.append(contentsOf: part.texts)
            }
            // 同一个链接常被投递方以 public.url 与 public.plain-text 两种形式各给一份 (Safari 等),
            // 不去重就会把一条链接抓两遍书 (第二次必然报"已在书架") 或多搜一次: 按内容去重、保留原序
            merged.texts = merged.texts.reduce(into: [String]()) { acc, text in
                if !acc.contains(text) { acc.append(text) }
            }
            completion(merged)
        }
    }

    /// 一条附件的取用类型 (下标即优先级)。
    private enum AttachmentKind {
        case fileURL
        case url
        case text

        var identifier: String {
            switch self {
            case .fileURL: return UTType.fileURL.identifier
            case .url: return UTType.url.identifier
            case .text: return UTType.plainText.identifier
            }
        }
    }

    /// 按优先级挑出这条附件该用哪个类型解 (通配 MIME 的分享常同时声明好几类)。
    private static func attachmentKind(of provider: NSItemProvider) -> AttachmentKind? {
        if provider.hasItemConformingToTypeIdentifier(AttachmentKind.fileURL.identifier) { return .fileURL }
        if provider.hasItemConformingToTypeIdentifier(AttachmentKind.url.identifier) { return .url }
        if provider.hasItemConformingToTypeIdentifier(AttachmentKind.text.identifier) { return .text }
        NSLog("[Legado] share extension got unsupported item: %@", String(describing: provider.registeredTypeIdentifiers))
        return nil
    }

    /// `public.file-url` 的交付形态: URL, 或旧系统版本用 Data 装着的 file-url。
    private static func fileURL(from item: Any?) -> URL? {
        if let url = item as? URL { return url }
        if let data = item as? Data { return URL(dataRepresentation: data, relativeTo: nil) }
        return nil
    }

    /// `public.url` 的交付形态解成文本 (网页链接就是给用户当"一段文本"分享的)。
    private static func text(fromURLItem item: Any?) -> String? {
        guard let url = fileURL(from: item) else { return nil }
        let text = url.absoluteString
        return text.isEmpty ? nil : text
    }

    /// `public.plain-text` 的交付形态: NSString, 或 Data (按 UTF-8 解)。
    private static func text(fromTextItem item: Any?) -> String? {
        if let text = item as? String { return text.isEmpty ? nil : text }
        if let data = item as? Data, let text = String(data: data, encoding: .utf8) {
            return text.isEmpty ? nil : text
        }
        return nil
    }
}
