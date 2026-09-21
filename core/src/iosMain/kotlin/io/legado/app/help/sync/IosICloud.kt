package io.legado.app.help.sync

import kotlin.concurrent.Volatile

/**
 * iCloud 同步总开关与容器标识 (进度 KV 通道 + 备份 Documents 通道共用)。
 *
 * 代码已就位但**默认不启用**: iCloud capability 需要付费开发者账号才能在 App ID 上打开,
 * 未开通时调用 iCloud API 只会静默失败 (容器 URL 恒为 null)。故本包所有入口都先过
 * [enabled] 判断, 关着就直接 return, 对现有 WebDav 通道零影响。
 *
 * # 启用四步 (付费账号到位后照做)
 * 1. **付费开发者账号**: Apple Developer Program ($99/年), Personal Team 无 iCloud capability。
 * 2. **Xcode / 开发者后台**: 给 App ID `io.legado.app.ios` 勾选 iCloud, 打开
 *    **Key-value storage** (进度通道) 与 **iCloud Documents** (备份通道),
 *    并创建容器 `iCloud.io.legado.app.ios` (即 [CONTAINER_ID])。
 * 3. **entitlements**: `iosApp/project.yml` 现在没有 entitlements 配置, 需新增
 *    `iosApp/iosApp.entitlements` 并在 target 的 `settings.base` 里加
 *    `CODE_SIGN_ENTITLEMENTS: iosApp/iosApp.entitlements`; entitlements 内容:
 *    ```xml
 *    <key>com.apple.developer.ubiquity-kvstore-identifier</key>
 *    <string>$(TeamIdentifierPrefix)$(CFBundleIdentifier)</string>
 *    <key>com.apple.developer.icloud-container-identifiers</key>
 *    <array><string>iCloud.io.legado.app.ios</string></array>
 *    <key>com.apple.developer.icloud-services</key>
 *    <array><string>CloudDocuments</string></array>
 *    <key>com.apple.developer.ubiquity-container-identifiers</key>
 *    <array><string>iCloud.io.legado.app.ios</string></array>
 *    ```
 *    再在 project.yml 的 `info.properties` 里加 (备份包在"文件"App 可见):
 *    ```yaml
 *    NSUbiquitousContainers:
 *      iCloud.io.legado.app.ios:
 *        NSUbiquitousContainerIsDocumentScopePublic: true
 *        NSUbiquitousContainerSupportedFolderLevels: Any
 *        NSUbiquitousContainerName: legado
 *    ```
 * 4. **注册**: 在 `IosProviderRegistry.registerIosProviders()` 末尾加一行
 *    [IosICloud.enable], 由它拉起 [IosICloudProgressSync] 的远端变更观察者。
 *
 * # 两个通道的职责划分
 * - [IosICloudProgressSync]: `NSUbiquitousKeyValueStore`, 只放阅读进度 (每本一个 JSON,
 *   几十字节), 高频写、秒级同步、不耗流量; 总容量上限 1MB / 1024 key。
 * - [IosICloudBackupSync]: iCloud Documents 容器, 放 [io.legado.app.help.storage.BackupShared]
 *   产出的完整备份 zip, 对标 WebDav 的 backup/restore/列举历史备份三个动作。
 *
 * 两者与 WebDav 并存, 互不替换: iCloud 是额外通道, WebDav 配置照旧生效。
 */
object IosICloud {

    /** iCloud Documents 容器标识, 必须与 entitlements 中的 container-identifiers 逐字一致。 */
    const val CONTAINER_ID: String = "iCloud.io.legado.app.ios"

    /** 总开关, 默认关闭 (未开通 iCloud capability 时任何调用都是空转)。 */
    @Volatile
    var enabled: Boolean = false
        private set

    /** 启用 iCloud 同步: 打开总开关并挂上 KV 远端变更观察者。宿主启动早期调用一次。 */
    fun enable() {
        if (enabled) return
        enabled = true
        IosICloudProgressSync.startObserving()
    }

    /** 关闭 iCloud 同步并摘掉观察者 (供设置项运行时切换)。 */
    fun disable() {
        if (!enabled) return
        enabled = false
        IosICloudProgressSync.stopObserving()
    }
}
