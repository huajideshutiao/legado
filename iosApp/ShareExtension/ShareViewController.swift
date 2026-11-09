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

/// 一次附件加载的结算结果 (跨线程移交的值: Sendable 值类型, 由加载回调生成、主线程记录)。
private enum LoadResult {
    /// 文本产出 (并入文本槽批次)
    case text(String)
    /// 文件已就地落进收件目录
    case fileDelivered
    /// 这一件失败: 带回给用户看的说明 (不静默丢件)
    case failure(String)
}

/// 一条附件的取用表示 (与激活谓词的分支一一镜像: 放行的每条附件都取得到, 文件计数两侧一致)。
///
/// 嵌套类型会随外层类型继承 global actor (SE-0316); 本分类在主线程产出、值只在主线程流转,
/// 放文件层避免无谓的隔离标注。
private enum AttachmentKind {
    /// 文件带 file-url 表示: loadItem 直取 URL (真名随 URL)
    case fileURL
    /// 文件只有具体类型表示: loadFileRepresentation 拿系统临时件 (回调返回即被删)
    case fileRepresentation(typeIdentifier: String)
    /// 网页链接 (非文件 URL; file-url 继承 public.url, 须排除)
    case url
    /// 纯文本 (无文件上下文, 落文本槽)
    case text
}

/// 一条待加载附件 (主线程分类的产物; NSItemProvider 的调用全部留在主线程)。
private struct ReceivingItem {
    let kind: AttachmentKind
    let provider: NSItemProvider
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

    /// 收件流程只跑一次的守卫: UIKit 生命周期会重复回调 viewDidAppear (呈现其上的弹窗收起等),
    /// 同一次分享输入只收一次。
    private var didStartDelivery = false

    /// 批次结果记录 (仅主线程读写): 下标 = 批次条目顺序, nil = 该件尚未结算。
    private var batchResults: [LoadResult?] = []

    /// 批次校验拒件说明 (主线程写入, 汇总时并入失败清单)。
    private var batchRejections: [String] = []

    /// 本批待加载附件 (主线程分类的产物)。
    private var batchItems: [ReceivingItem] = []

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !didStartDelivery else { return }
        didStartDelivery = true
        startDelivery(plan: receivingPlan())
    }

    /// 主线程读取分享附件并按分类做批次校验 (分类与数量约束与激活谓词同一套):
    /// 文件总数 ≤ 1、网页链接总数 ≤ 1、文本不限; 未受支持或超量的一律给出可见拒件说明,
    /// 不静默跳过 —— 分享面板放行了批次之外的输入时, 用户必须知道哪一件没被接收。
    private func receivingPlan() -> (accepted: [ReceivingItem], rejections: [String]) {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else {
            return ([], [])
        }
        var accepted: [ReceivingItem] = []
        var rejections: [String] = []
        var fileSeen = false
        var webURLSeen = false
        for provider in items.compactMap({ $0.attachments }).flatMap({ $0 }) {
            guard let kind = Self.attachmentKind(of: provider) else {
                rejections.append("有一件分享内容不是本应用能接收的类型，没有接收。")
                continue
            }
            switch kind {
            case .fileURL, .fileRepresentation:
                if fileSeen {
                    rejections.append("本次分享只接收一个文件，超出的没有接收。")
                } else {
                    fileSeen = true
                    accepted.append(ReceivingItem(kind: kind, provider: provider))
                }
            case .url:
                if webURLSeen {
                    rejections.append("本次分享只接收一个网页链接，超出的没有接收。")
                } else {
                    webURLSeen = true
                    accepted.append(ReceivingItem(kind: kind, provider: provider))
                }
            case .text:
                accepted.append(ReceivingItem(kind: kind, provider: provider))
            }
        }
        return (accepted, rejections)
    }

    /// 收件主流程: 分享面板的附件分类与批次结果记录都在主线程 (`UIViewController` 是
    /// @MainActor, `extensionContext` 与 `NSItemProvider` 的调用都留在主线程); 容器/目录/
    /// 文件物化/文本落盘是明确 nonisolated 的文件原语, 在后台队列与加载回调内完成 (临时文件
    /// 生命周期不许推迟); 回调与后台只把 Sendable 结果值经主 actor 交回记录/汇总。
    private func startDelivery(plan: (accepted: [ReceivingItem], rejections: [String])) {
        guard !plan.accepted.isEmpty else {
            // 分享面板给过附件才会走到收件: 全被拒时拒绝原因即整次结果,
            // 一条附件都没解出来才用通用文案。
            finish(plan.rejections.isEmpty
                ? ["没能从分享内容里取到文件或文本，未收到任何内容。"]
                : plan.rejections)
            return
        }
        batchRejections = plan.rejections
        batchItems = plan.accepted
        batchResults = Array(repeating: nil, count: plan.accepted.count)
        // @Sendable 标注: 闭包不随外层方法继承 @MainActor (SE-0316), 体内只调 nonisolated
        // 文件原语; 回主线程一律经主 actor (Task { @MainActor })。
        DispatchQueue.global(qos: .userInitiated).async { @Sendable in
            do {
                let container = try self.containerURL()
                let inbox = try self.legadoDirectory(in: container, legadoInboxDirName)
                let textSlot = try self.legadoDirectory(in: container, legadoTextDirName)
                Task { @MainActor in
                    self.loadAndDeliver(inbox: inbox, textSlot: textSlot)
                }
            } catch {
                NSLog("[Legado] share extension: container unavailable: %@", Self.unavailableMessage(of: error))
                Task { @MainActor in
                    self.finish([Self.unavailableMessage(of: error)])
                }
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

    /// 共享容器本体; 拿不到时抛 [InboxUnavailable.noSharedContainer]。
    /// (纯文件原语, 不涉 UI 状态; nonisolated 供后台队列与加载回调直接调用。)
    private nonisolated func containerURL() throws -> URL {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: legadoAppGroupId
        ) else { throw InboxUnavailable.noSharedContainer }
        return container
    }

    /// 容器内的一个 Legado 目录 (不存在时创建); 建不出来抛错, 由调用方转成用户可见说明。
    /// (纯文件原语, nonisolated 供后台队列直接调用。)
    private nonisolated func legadoDirectory(in container: URL, _ name: String) throws -> URL {
        let dir = container.appendingPathComponent(name, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        } catch {
            NSLog("[Legado] share extension: create %@ failed: %@", name, error.localizedDescription)
            throw InboxUnavailable.cannotCreateInbox(error.localizedDescription)
        }
        return dir
    }

    /// 目录建不出来的统一措辞 (容器在但子目录写不了)。 (纯值转换, nonisolated。)
    private nonisolated static func unavailableMessage(of error: Error) -> String {
        (error as? InboxUnavailable)?.userMessage
            ?? InboxUnavailable.cannotCreateInbox(error.localizedDescription).userMessage
    }

    /// `public.file-url` 附件: 直取 URL, 真名随 URL; 原件可能在别的 App 的沙盒里,
    /// 读前起 security-scoped 授权。 (纯文件原语, nonisolated 供加载回调直接调用。)
    private nonisolated static func deliverFileURLItem(
        _ loaded: Any?, error: Error?, suggestedName: String?, inbox: URL
    ) -> LoadResult {
        if let error {
            NSLog("[Legado] share extension loadItem failed: %@", error.localizedDescription)
            return .failure("有一件文件没能接收：\(error.localizedDescription)")
        }
        guard let url = fileURL(from: loaded) else {
            return .failure("有一件文件没能接收：没有拿到文件内容。")
        }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return deliverOneFile(
            url,
            name: deliveryFileName(url.lastPathComponent, suggestedName),
            inbox: inbox)
    }

    /// 文件表示附件: 官方契约 (`loadFileRepresentation(forTypeIdentifier:completionHandler:)`,
    /// Foundation) 是回调拿到的临时文件 "which the system deletes when the completion handler
    /// returns" —— 物化 (整份拷进容器) 必须在回调内完成。 (纯文件原语, nonisolated 供加载回调直接调用。)
    private nonisolated static func deliverRepresentationItem(
        _ itemURL: URL?, error: Error?, suggestedName: String?, inbox: URL
    ) -> LoadResult {
        if let error {
            NSLog("[Legado] share extension loadFileRepresentation failed: %@", error.localizedDescription)
            return .failure("有一件文件没能接收：\(error.localizedDescription)")
        }
        guard let itemURL else {
            return .failure("有一件文件没能接收：没有拿到文件内容。")
        }
        return deliverOneFile(
            itemURL,
            name: deliveryFileName(suggestedName, itemURL.lastPathComponent),
            inbox: inbox)
    }

    /// 落盘名: 依序取第一个可用名 (非空、不以点开头、不含路径分隔符)。
    /// 建议名是空串时不算可用, 退到下一个候选 (file-url 的真名 / 系统临时件名);
    /// 都不可用返回 nil, 由 [Self.deliverOneFile] 明确拒件 —— 不伪造文件名、不补造扩展名
    /// (无扩展名的可用名照常落盘, 由宿主的未知文件确认流程接住)。 (纯值转换, nonisolated。)
    private nonisolated static func deliveryFileName(_ candidates: String?...) -> String? {
        for candidate in candidates {
            if let candidate, !candidate.isEmpty, !candidate.hasPrefix("."), !candidate.contains("/") {
                return candidate
            }
        }
        return nil
    }

    /// 把一件文件拷进收件目录 (隐藏临时名 + 成功后原子改名, 与宿主取件的隐藏临时件契约同一套;
    /// 只做一次整份拷贝, 不转 Data、不再落第二份中间件)。
    /// 名字不可用 (空/隐藏名/含路径分隔) 时明确拒件: 宿主取件跳过隐藏名 (与临时件 `.name.part`
    /// 同一契约), 带路径分隔的名字会写进子目录, 都不能当完整件交付。
    /// (纯文件原语, nonisolated 供加载回调直接调用。)
    private nonisolated static func deliverOneFile(_ source: URL, name: String?, inbox: URL) -> LoadResult {
        guard let name, !name.isEmpty, !name.hasPrefix("."), !name.contains("/") else {
            NSLog("[Legado] share extension: attachment without usable file name dropped")
            return .failure("有一件分享内容没有可用于接收的文件名，没有接收。")
        }
        let dest = inbox.appendingPathComponent(name)
        let temp = tempFile(for: dest)
        do {
            // copyItem 要求目标不存在: 上一次被系统杀掉的拷贝会停在临时名上, 先清掉
            try? FileManager.default.removeItem(at: temp)
            try FileManager.default.copyItem(at: source, to: temp)
            try publish(temp: temp, to: dest)
            NSLog("[Legado] share extension delivered file: %@", name)
            return .fileDelivered
        } catch {
            NSLog("[Legado] share extension deliver failed (%@): %@", name, error.localizedDescription)
            return .failure("「\(name)」没能接收：\(error.localizedDescription)")
        }
    }

    /// 文本: 每条落一个 `.txt` (文件名带时间戳与序号, 多条分享不会互相覆盖)。
    /// (纯文件原语, nonisolated 供后台队列直接调用。)
    private nonisolated static func deliverTexts(_ texts: [String], to slot: URL) -> (Int, [String]) {
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
    /// 宿主扫目录时跳过它, 因此半截内容不会被当成完整件取走。 (纯路径值转换, nonisolated。)
    private nonisolated static func tempFile(for dest: URL) -> URL {
        let dir = dest.deletingLastPathComponent()
        return dir.appendingPathComponent(".\(dest.lastPathComponent).part")
    }

    /// 临时名 → 正式名的原子改名 (同目录 rename): 目标已存在时被整份替换。
    ///
    /// `Darwin.rename` 与宿主侧 `stage` 用的是同一个 POSIX 调用 —— Swift 里 `UIViewController`
    /// 经 `UIResponder` 在 iOS 16+ 也有一个 `rename(_:)` 实例方法, 裸写 `rename` 会解析成那个
    /// 成员, 所以必须带 `Darwin.` 限定。 (纯文件原语, nonisolated。)
    private nonisolated static func publish(temp: URL, to dest: URL) throws {
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

    /// 逐条登记加载 (主线程): 文件的两条表示路都接 (分支与激活谓词同一张清单, 见
    /// [Self.attachmentKind(of:)]) ——
    /// - `public.file-url` 经 `loadItem` 直取 URL (真名随 URL, 读前起 security-scoped 授权);
    /// - 只有具体文件类型的经 `loadFileRepresentation` 拿文件表示 —— 官方契约是回调拿到的
    ///   临时文件在回调返回时被系统删除, 物化 (整份拷进容器) 必须在回调内完成。
    ///
    /// 两个加载回调都被 Foundation 声明为 @Sendable 且可能在后台队列执行: 回调内只调
    /// nonisolated 文件原语/值转换并同步完成复制, 再把 Sendable 结果值经主 actor 交回
    /// [recordResult] 记录汇总; 不在跨域闭包里捕获可变数组。
    private func loadAndDeliver(inbox: URL, textSlot: URL) {
        for (index, item) in batchItems.enumerated() {
            switch item.kind {
            case .fileURL:
                let suggestedName = item.provider.suggestedName
                item.provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { loaded, error in
                    let result = Self.deliverFileURLItem(loaded, error: error, suggestedName: suggestedName, inbox: inbox)
                    Task { @MainActor in
                        self.recordResult(at: index, result, textSlot: textSlot)
                    }
                }
            case .fileRepresentation(let typeIdentifier):
                let suggestedName = item.provider.suggestedName
                item.provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { itemURL, error in
                    // 官方契约: 临时文件在本回调返回时被系统删除 —— 物化必须在回调内完成
                    let result = Self.deliverRepresentationItem(
                        itemURL, error: error, suggestedName: suggestedName, inbox: inbox)
                    Task { @MainActor in
                        self.recordResult(at: index, result, textSlot: textSlot)
                    }
                }
            case .url:
                item.provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { loaded, error in
                    let result: LoadResult
                    if let error {
                        NSLog("[Legado] share extension loadItem failed: %@", error.localizedDescription)
                        result = .failure("有一条网页链接没能接收：\(error.localizedDescription)")
                    } else if let text = Self.text(fromURLItem: loaded) {
                        result = .text(text)
                    } else {
                        result = .failure("有一条网页链接没能接收：没有拿到链接内容。")
                    }
                    Task { @MainActor in
                        self.recordResult(at: index, result, textSlot: textSlot)
                    }
                }
            case .text:
                item.provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { loaded, error in
                    let result: LoadResult
                    if let error {
                        NSLog("[Legado] share extension loadItem failed: %@", error.localizedDescription)
                        result = .failure("有一条文本没能接收：\(error.localizedDescription)")
                    } else if let text = Self.text(fromTextItem: loaded) {
                        result = .text(text)
                    } else {
                        result = .failure("有一条文本没能接收：没有拿到文本内容。")
                    }
                    Task { @MainActor in
                        self.recordResult(at: index, result, textSlot: textSlot)
                    }
                }
            }
        }
    }

    /// 批次结果记录与汇总 (仅主线程): 最后一件结算时合并拒件与文本, 文本落盘交后台, 收尾回主线程。
    private func recordResult(at index: Int, _ result: LoadResult, textSlot: URL) {
        batchResults[index] = result
        guard !batchResults.contains(where: { $0 == nil }) else { return }
        var texts: [String] = []
        var failures = batchRejections
        var fileDelivered = false
        for settled in batchResults.compactMap({ $0 }) {
            switch settled {
            case .text(let text): texts.append(text)
            case .fileDelivered: fileDelivered = true
            case .failure(let message): failures.append(message)
            }
        }
        // 同一个链接/文本常被投递方以 public.url 与 public.plain-text 两种形式各给一份 (Safari 等),
        // 不去重就会把一条链接抓两遍书 (第二次必然报"已在书架") 或多搜一次: 按内容去重、保留原序
        let settledTexts = texts.reduce(into: [String]()) { acc, text in
            if !acc.contains(text) { acc.append(text) }
        }
        // 至此被接受的每条附件都已结算成文本/文件/失败之一: 全失败时 failures 非空
        guard fileDelivered || !settledTexts.isEmpty else {
            finish(failures)
            return
        }
        if settledTexts.isEmpty {
            // 只收到文件且已落盘: 无文本待写, 直接收尾
            finish(failures)
            return
        }
        let settledFailures = failures
        DispatchQueue.global(qos: .userInitiated).async { @Sendable in
            let (deliveredTexts, textFailures) = Self.deliverTexts(settledTexts, to: textSlot)
            Task { @MainActor in
                if deliveredTexts > 0 { self.wakeHost() }
                self.finish(settledFailures + textFailures)
            }
        }
    }

    /// 非文本文件支持类型 (UTI 逐字): 有具体类型即按文件收 —— 提供方可以只给文件表示、
    /// 不给 file-url。plain-text 不在此列: 没有文件上下文的选中文本按文字走文本槽,
    /// 同时带 file-url 的才算 TXT 文件 (见 [Self.attachmentKind(of:)])。
    ///
    /// ⚠️ 必须与 ShareExtension/Info.plist 与 project.yml 里 `NSExtensionActivationRule`
    /// 谓词的 UTI-CONFORMS-TO 文件清单逐字一致 (激活与取件同一张清单); 清单 = iOS 端既有
    /// 导入/播放链实际接的格式: epub/json/pdf (导入链)、zip/tar/gz/tgz (压缩包链)、mp4/mov
    /// (播放链 + shared 分发清单)。
    private static let nonTextFileTypeIdentifiers: [String] = [
        UTType.epub.identifier,
        UTType.json.identifier,
        UTType.pdf.identifier,
        UTType.zip.identifier,
        UTType.tar.identifier,
        UTType.gzip.identifier,
        "org.gnu.gnu-zip-tar-archive",
        UTType.mpeg4Movie.identifier,
        UTType.quickTimeMovie.identifier,
    ]

    /// 按优先级挑出这条附件该用哪个表示解 (通配 MIME 的分享常同时声明好几类):
    /// 1. 文件先于文本: 带清单内非文本类型即按文件收 (有 file-url 走 file-url 直取, 否则走
    ///    文件表示); plain-text 同时带 file-url 的 (TXT 文件) 也按文件收 —— 同时带文字表示的
    ///    书籍文件不降级成搜索文本;
    /// 2. 链接: `public.url` 且非 `public.file-url` (它继承 public.url, 不排除会把文件当链接放行);
    /// 3. 文本: `public.plain-text`。
    private static func attachmentKind(of provider: NSItemProvider) -> AttachmentKind? {
        let nonTextType = nonTextFileTypeIdentifiers.first {
            provider.hasItemConformingToTypeIdentifier($0)
        }
        if let nonTextType {
            if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) { return .fileURL }
            return .fileRepresentation(typeIdentifier: nonTextType)
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier)
            && provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            return .fileURL
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier)
            && !provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            return .url
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) { return .text }
        NSLog("[Legado] share extension got unsupported item: %@", String(describing: provider.registeredTypeIdentifiers))
        return nil
    }

    /// `public.file-url` 的交付形态: URL, 或旧系统版本用 Data 装着的 file-url。 (纯值转换, nonisolated。)
    private nonisolated static func fileURL(from item: Any?) -> URL? {
        if let url = item as? URL { return url }
        if let data = item as? Data { return URL(dataRepresentation: data, relativeTo: nil) }
        return nil
    }

    /// `public.url` 的交付形态解成文本 (网页链接就是给用户当"一段文本"分享的)。 (纯值转换, nonisolated。)
    private nonisolated static func text(fromURLItem item: Any?) -> String? {
        guard let url = fileURL(from: item) else { return nil }
        let text = url.absoluteString
        return text.isEmpty ? nil : text
    }

    /// `public.plain-text` 的交付形态: NSString, 或 Data (按 UTF-8 解)。 (纯值转换, nonisolated。)
    private nonisolated static func text(fromTextItem item: Any?) -> String? {
        if let text = item as? String { return text.isEmpty ? nil : text }
        if let data = item as? Data, let text = String(data: data, encoding: .utf8) {
            return text.isEmpty ? nil : text
        }
        return nil
    }
}
