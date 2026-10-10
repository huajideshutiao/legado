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
            当前安装包的签名不含共享容器（App Groups），无法把文件交给 Legado 导入。
            可换用带该能力的签名安装，或先把文件存到「文件」App，再用 Legado 的「导入本地书籍」导入。
            """
        case .cannotCreateInbox(let reason):
            return "无法创建接收目录：\(reason)"
        }
    }
}

/// 分享扩展的入口 ViewController (已接线)。
///
/// # 交付通道
/// 分享扩展**不能拉起宿主 App** (扩展进程里没有通往宿主的 openURL 通道, Apple 限制),
/// 故走业界唯一通道: 扩展把附件拷进 App Group 共享容器, 宿主下次启动由 Kotlin 侧
/// `IosShareInbox` 扫该目录取件 (→ 既有的 `handleIosLaunchRequest` → 导入分发),
/// 取件后清掉容器里的原件。
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
/// 那个基类是"发帖/发消息"形态 (标题栏 + 预览 + 发送按钮), 与本应用"把文件交给自己导入"
/// 的语义不符; 用裸 UIViewController 才能做成"接手即完成"的后台式交接
/// (只有收不下时才停下来跟用户说一声)。
final class ShareViewController: UIViewController {

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        loadAttachments { [weak self] urls in
            guard let self else { return }
            // 本扩展的激活规则只声明了文件 (见 Info.plist), 取不到文件 URL 就是一件都没收到
            guard !urls.isEmpty else {
                self.finish(["没能从分享内容里取到文件，未收到任何内容。"])
                return
            }
            self.deliver(urls) { [weak self] failures in
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

    /// 把附件拷进 App Group 容器的收件目录。
    ///
    /// 回调带回"用户能看懂的失败说明" (空数组 = 全部收下): 容器取不到、收件目录建不出、
    /// 单件拷贝失败都会带出来, 由 [finish] 摆给用户看 —— 用户在这里点了本应用却什么都没发生时,
    /// 至少知道为什么。
    /// 单件落盘走"隐藏临时名 + 成功后原子改名": 宿主在拷贝期间取件只会看到完整文件或没有文件
    /// (宿主扫收件目录时跳过隐藏项), 同名覆盖也只在整份拷完之后发生。
    private func deliver(_ urls: [URL], completion: @escaping ([String]) -> Void) {
        let inbox: URL
        switch inboxDirectory() {
        case .success(let url):
            inbox = url
        case .failure(let unavailable):
            NSLog("[Legado] share extension: inbox unavailable: %@", unavailable.userMessage)
            completion([unavailable.userMessage])
            return
        }
        // 拷贝放后台队列: 分享进来的可能是上百 MB 的书/视频, 占着主队列做 IO 只会拖长
        // 扩展存活时间 (回调在拷贝完成后才 completeRequest, 不会被提前杀)
        DispatchQueue.global(qos: .userInitiated).async {
            var failures: [String] = []
            for url in urls {
                let name = url.lastPathComponent
                guard !name.isEmpty else {
                    NSLog("[Legado] share extension: attachment without file name dropped")
                    failures.append("有一件分享内容没有文件名，没能接收。")
                    continue
                }
                // 附件可能位于别的 App 的容器里, 读之前要起 security-scoped 授权
                // (沙盒内 URL 返回 false, 直接忽略)
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let dest = inbox.appendingPathComponent(name)
                let temp = inbox.appendingPathComponent(".\(name).part")
                do {
                    if FileManager.default.fileExists(atPath: temp.path) {
                        try FileManager.default.removeItem(at: temp)
                    }
                    try FileManager.default.copyItem(at: url, to: temp)
                    // 原子改名 (同目录 rename): 目标已存在时被整份替换, 取件方只会看到完整文件
                    // (半截内容只出现在隐藏临时名上, 宿主扫收件目录时跳过隐藏项)
                    guard rename(temp.path, dest.path) == 0 else {
                        let code = errno
                        throw NSError(
                            domain: NSPOSIXErrorDomain,
                            code: Int(code),
                            userInfo: [
                                NSLocalizedDescriptionKey: String(cString: strerror(code)),
                            ]
                        )
                    }
                    NSLog("[Legado] share extension delivered: %@", name)
                } catch {
                    try? FileManager.default.removeItem(at: temp)
                    NSLog(
                        "[Legado] share extension deliver failed (%@): %@",
                        name,
                        error.localizedDescription
                    )
                    failures.append("「\(name)」没能接收：\(error.localizedDescription)")
                }
            }
            DispatchQueue.main.async { completion(failures) }
        }
    }

    /// App Group 容器的收件目录; 拿不到时带上原因 (未签名产物走的就是 `.noSharedContainer`)。
    private func inboxDirectory() -> Result<URL, InboxUnavailable> {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: legadoAppGroupId
        ) else { return .failure(.noSharedContainer) }
        let inbox = container.appendingPathComponent(legadoInboxDirName, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        } catch {
            NSLog("[Legado] share extension: create inbox failed: %@", error.localizedDescription)
            return .failure(.cannotCreateInbox(error.localizedDescription))
        }
        return .success(inbox)
    }

    /// 把 `extensionContext.inputItems` 里的文件附件解出成可读的 URL。
    ///
    /// 用 `loadItem` (而不是 `loadFileRepresentation`): 前者拿到的是系统给的原始 item,
    /// 对 `public.file-url` 就是文件 URL; 后者会把文件复制到临时目录并**要求调用方在
    /// 回调返回后立刻处理**, 多一层临时文件生命周期要管, 而交付本来就要整份拷进容器。
    private func loadAttachments(completion: @escaping ([URL]) -> Void) {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else {
            completion([])
            return
        }
        let providers = items
            .compactMap { $0.attachments }
            .flatMap { $0 }

        guard !providers.isEmpty else {
            completion([])
            return
        }

        // 结果按 providers 下标回填, 保证顺序稳定 (并发 loadItem 的回调顺序不定)
        var collected = [URL?](repeating: nil, count: providers.count)
        let group = DispatchGroup()
        for (index, provider) in providers.enumerated() {
            // 只认文件 (本扩展的激活规则也只声明了文件), 其它类型直接跳过
            guard provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) else {
                continue
            }
            group.enter()
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, error in
                defer { group.leave() }
                if let error = error {
                    NSLog("[Legado] share extension loadItem failed: %@", error.localizedDescription)
                    return
                }
                if let url = item as? URL {
                    collected[index] = url
                } else if let data = item as? Data,
                          let url = URL(dataRepresentation: data, relativeTo: nil) {
                    // 部分系统版本把 file-url 以 Data 形式交付
                    collected[index] = url
                } else {
                    NSLog("[Legado] share extension got unsupported item: %@", String(describing: item))
                }
            }
        }
        group.notify(queue: .main) {
            completion(collected.compactMap { $0 })
        }
    }
}
