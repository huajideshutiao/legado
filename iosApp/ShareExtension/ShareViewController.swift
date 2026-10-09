import UIKit
import UniformTypeIdentifiers

/// 分享扩展的入口 ViewController (**基建, 未接线**)。
///
/// # 为什么现在是"只读不交付"
/// 交付通道要 App Group 共享容器 (分享扩展不能拉起宿主 App, Apple 限制), 而未签名
/// ipa 拿不到 entitlement, 所以"写进容器 + 宿主取件"两头都还不能做。本类当前只把
/// 系统交给扩展的附件**读出来并落一条日志**, 不做任何交付, 读完立刻结束请求 ——
/// 这样既有真实的取件代码可复用, 又不会让分享面板卡住。
///
/// 完整背景与"接线三处"见 `iosApp/project.yml` 的 `ShareExtension` 段。
///
/// # 接线时要改的只有 [deliver]
/// 把实现换成"写进 App Group 容器 (FileManager.containerURL(forSecurityApplicationGroupIdentifier:))",
/// 并在宿主启动路径加一次容器扫描 (Kotlin `IosLaunchRequests` → `LaunchRequest.ImportFile`)。
/// [loadAttachments] 与入口壳都不用动。
///
/// # 为什么不用 SLComposeServiceViewController
/// 那个基类是"发帖/发消息"形态 (标题栏 + 预览 + 发送按钮), 与本应用"把文件交给自己导入"
/// 的语义不符; 用裸 UIViewController 才能做成"接手即完成"的后台式交接。
final class ShareViewController: UIViewController {

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        loadAttachments { [weak self] urls in
            // 基建阶段: 只记录, 不交付 (没有容器可写)。
            for url in urls {
                NSLog("[Legado] share extension received file (not delivered): %@", url.absoluteString)
            }
            DispatchQueue.main.async {
                self?.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            }
        }
    }

    /// 把 `extensionContext.inputItems` 里的文件附件解出成可读的 URL。
    ///
    /// 用 `loadItem` (而不是 `loadFileRepresentation`): 前者拿到的是系统给的原始 item,
    /// 对 `public.file-url` 就是文件 URL; 后者会把文件复制到临时目录并**要求调用方在
    /// 回调返回后立刻处理**, 交付逻辑还没接时反而会引入"临时文件已被回收"的坑。
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
