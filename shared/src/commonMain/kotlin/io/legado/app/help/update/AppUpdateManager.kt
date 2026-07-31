package io.legado.app.help.update

import kotlin.concurrent.Volatile

/**
 * 一个平台的完整更新策略 = 检测层 + 执行层。
 *
 * @param checker      版本检测实现
 * @param action       拿到本平台安装包时的执行方式
 * @param fallback     拿不到安装包时的降级执行方式
 */
data class UpdateStrategy(
    val checker: UpdateChecker,
    val action: UpdateAction,
    val fallback: UpdateAction = UpdateAction.OPEN_DOWNLOAD_PAGE,
) {
    fun actionFor(info: UpdateCheckInfo): UpdateAction =
        if (info.hasAsset) action else fallback
}

/**
 * 唯一策略配置点 —— 换分发渠道只改这张表, 各端与 UI 层都不动。
 *
 * 当前 (全平台侧载, 不上架任何市场):
 * | 平台     | 检测                     | 执行                                   |
 * |----------|--------------------------|----------------------------------------|
 * | Android  | GitHubReleaseChecker     | DIRECT_INSTALL (下载 apk → 系统安装器) |
 * | Desktop  | GitHubReleaseChecker     | DOWNLOAD_AND_PROMPT, 无产物降级打开页  |
 * | iOS      | GitHubReleaseChecker     | SIDELOAD_DEEP_LINK (SideStore/AltStore)|
 * | 鸿蒙     | GitHubReleaseChecker     | OPEN_DOWNLOAD_PAGE (hap 需 hdc/DevEco) |
 *
 * 未来上架时只需在这里换绑, 例如 iOS 上架 App Store:
 * ```
 * UpdateStrategies.register(
 *     UpdatePlatform.IOS,
 *     UpdateStrategy(AppStoreChecker(bundleId), UpdateAction.OPEN_STORE, UpdateAction.OPEN_STORE)
 * )
 * ```
 * 鸿蒙同理换 [AppGalleryChecker] + [UpdateAction.OPEN_STORE]。
 * desktop 若 CI 补齐 msi/dmg/deb 产物, 无需改结构 —— [UpdateStrategy.actionFor] 自然从
 * fallback 升级为 DOWNLOAD_AND_PROMPT。
 */
object UpdateStrategies {

    private val gitHub by lazy { GitHubReleaseChecker() }

    private val overrides = mutableMapOf<UpdatePlatform, UpdateStrategy>()

    fun register(platform: UpdatePlatform, strategy: UpdateStrategy) {
        overrides[platform] = strategy
    }

    fun of(platform: UpdatePlatform): UpdateStrategy = overrides[platform] ?: default(platform)

    fun default(platform: UpdatePlatform): UpdateStrategy = when (platform) {
        UpdatePlatform.ANDROID -> UpdateStrategy(gitHub, UpdateAction.DIRECT_INSTALL)
        UpdatePlatform.IOS -> UpdateStrategy(gitHub, UpdateAction.SIDELOAD_DEEP_LINK)
        UpdatePlatform.OHOS -> UpdateStrategy(gitHub, UpdateAction.OPEN_DOWNLOAD_PAGE)
        else -> UpdateStrategy(gitHub, UpdateAction.DOWNLOAD_AND_PROMPT)
    }
}

/**
 * 当前端的运行时信息 (平台/版本号/渠道/架构), 由各端启动时注册。
 *
 * Android 端不走这里 (AboutActivity 直接用 [AppUpdateShared.check]);
 * desktop/iOS/鸿蒙在入口注册后, shared 的关于页即可自行完成"检查更新"全流程。
 */
interface AppUpdateEnvironment {
    val platform: UpdatePlatform
    val currentVersionName: String
    val currentAppVariant: AppVariant get() = AppVariant.OFFICIAL
    val supportedAbis: List<String> get() = listOf("arm64")
    val updateToVariant: String get() = "default_version"
}

/**
 * 检查更新统一入口: 取策略 → 检测 → 执行, 平台差异全部收敛在 [UpdateStrategies]。
 */
object AppUpdateManager {

    @Volatile
    private var environment: AppUpdateEnvironment? = null

    fun register(env: AppUpdateEnvironment) {
        environment = env
    }

    fun environmentOrNull(): AppUpdateEnvironment? = environment

    /** 是否显示"检查更新"入口 (未注册环境的端隐藏)。 */
    fun isAvailable(): Boolean = environment != null

    suspend fun check(): UpdateCheckResult {
        val env = environment ?: return UpdateCheckResult.Failed(
            IllegalStateException("AppUpdateManager 未注册 AppUpdateEnvironment")
        )
        return UpdateStrategies.of(env.platform).checker.check(
            UpdateCheckRequest(
                platform = env.platform,
                currentVersionName = env.currentVersionName,
                currentAppVariant = env.currentAppVariant,
                updateToVariant = env.updateToVariant,
                supportedAbis = env.supportedAbis,
            )
        )
    }

    /** 该新版本在当前端的执行方式 (无环境时按"打开下载页"兜底)。 */
    fun actionFor(info: UpdateCheckInfo): UpdateAction {
        val env = environment ?: return UpdateAction.OPEN_DOWNLOAD_PAGE
        return UpdateStrategies.of(env.platform).actionFor(info)
    }

    /** 对已检测到的新版本执行更新; 端上执行器拒绝时降级为打开页面。 */
    suspend fun execute(info: UpdateCheckInfo) {
        if (!UpdateExecutors.get().execute(actionFor(info), info)) {
            OpenPageUpdateExecutor.execute(UpdateAction.OPEN_DOWNLOAD_PAGE, info)
        }
    }
}
