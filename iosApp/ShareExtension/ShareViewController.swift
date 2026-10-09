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

/// 分享扩展的入口 ViewController (已接线)。
///
/// # 交付通道
/// 分享扩展**不能拉起宿主 App** (扩展进程里没有通往宿主的 openURL 通道, Apple 限制),
/// 故走业界唯一通道: 扩展把附件拷进 App Group 共享容器, 宿主下次启动由 Kotlin 侧
/// `IosShareInbox` 扫该目录取件 (→ 既有的 `handleIosLaunchRequest` → 导入分发),
/// 取件后清掉容器里的原件。
///
/// # 生效前提 (未签名产物上不生效, 且不会崩)
/// App Group 靠 entitlement 落地, 而本仓 CI 产出的 ipa 是**未签名**的
/// (CODE_SIGNING_ALLOWED=NO, 与 iCloud 容器同样原因), 拿不到 entitlement →
/// `FileManager.containerURL(forSecurityApplicationGroupIdentifier:)` 恒返回 nil →
/// [deliver] 只记日志、不交付, 扩展照常"接手即完成"退出。
/// 签名 (侧载工具重签 / 正式签名) 且 App ID 勾上 App Groups 后才真正生效。
///
/// 完整背景见 `iosApp/project.yml` 的 `ShareExtension` 段。
///
/// # 为什么不用 SLComposeServiceViewController
/// 那个基类是"发帖/发消息"形态 (标题栏 + 预览 + 发送按钮), 与本应用"把文件交给自己导入"
/// 的语义不符; 用裸 UIViewController 才能做成"接手即完成"的后台式交接。
final class ShareViewController: UIViewController {

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        loadAttachments { [weak self] urls in
            guard let self else { return }
            self.deliver(urls) {
                DispatchQueue.main.async {
                    self.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
                }
            }
        }
    }

    /// 把附件拷进 App Group 容器的收件目录。
    ///
    /// 容器取不到 (未签名产物没有 entitlement / 建目录失败) 时只记日志并回调 ——
    /// 分享面板不被卡住, 也不崩。
    /// 同名文件覆盖写: 与宿主侧导入的"同名覆盖"语义一致, 也避免 copyItem 因目标已存在而失败。
    private func deliver(_ urls: [URL], completion: @escaping () -> Void) {
        guard let inbox = inboxDirectory() else {
            for url in urls {
                NSLog(
                    "[Legado] share extension: App Group container unavailable, dropped: %@",
                    url.lastPathComponent
                )
            }
            completion()
            return
        }
        // 拷贝放后台队列: 分享进来的可能是上百 MB 的书/视频, 占着主队列做 IO 只会拖长
        // 扩展存活时间 (回调在拷贝完成后才 completeRequest, 不会被提前杀)
        DispatchQueue.global(qos: .userInitiated).async {
            for url in urls {
                let name = url.lastPathComponent
                guard !name.isEmpty else { continue }
                // 附件可能位于别的 App 的容器里, 读之前要起 security-scoped 授权
                // (沙盒内 URL 返回 false, 直接忽略)
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let dest = inbox.appendingPathComponent(name)
                do {
                    if FileManager.default.fileExists(atPath: dest.path) {
                        try FileManager.default.removeItem(at: dest)
                    }
                    try FileManager.default.copyItem(at: url, to: dest)
                    NSLog("[Legado] share extension delivered: %@", name)
                } catch {
                    NSLog(
                        "[Legado] share extension deliver failed (%@): %@",
                        name,
                        error.localizedDescription
                    )
                }
            }
            DispatchQueue.main.async { completion() }
        }
    }

    /// App Group 容器的收件目录; 容器或目录不可用返回 nil (未签名产物走的就是这条)。
    private func inboxDirectory() -> URL? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: legadoAppGroupId
        ) else { return nil }
        let inbox = container.appendingPathComponent(legadoInboxDirName, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        } catch {
            NSLog("[Legado] share extension: create inbox failed: %@", error.localizedDescription)
            return nil
        }
        return inbox
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
