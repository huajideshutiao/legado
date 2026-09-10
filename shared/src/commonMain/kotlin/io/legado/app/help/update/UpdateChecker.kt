package io.legado.app.help.update

/**
 * 更新检测层接口。
 *
 * 实现可换而不动核心:
 * - [GitHubReleaseChecker] 查项目自己的 GitHub Release (当前全平台共用)
 * - [AppStoreChecker] iTunes Lookup API (预留: iOS 上架后启用)
 * - [AppGalleryChecker] 华为应用市场 (预留: 鸿蒙上架后启用)
 *
 * 组合方式见 [UpdateCheckers]。
 */
interface UpdateChecker {
    suspend fun check(request: UpdateCheckRequest): UpdateCheckResult
}

/**
 * 检测请求 (渠道无关)。
 *
 * @param platform          当前平台, 决定资产后缀匹配
 * @param currentVersionName 已安装版本号
 * @param currentAppVariant  已安装渠道变体 (Android release/releaseA/beta)
 * @param updateToVariant    更新渠道偏好 (PreferKey.updateToVariant)
 * @param supportedAbis      设备 ABI/arch (Android Build.SUPPORTED_ABIS / JVM os.arch / arm64)
 */
data class UpdateCheckRequest(
    val platform: UpdatePlatform,
    val currentVersionName: String,
    val currentAppVariant: AppVariant = AppVariant.OFFICIAL,
    val updateToVariant: String = "default_version",
    val supportedAbis: List<String> = emptyList(),
) {
    /**
     * 本次要查的渠道 (对照原版 AppUpdate.getCheckVariant):
     * 用户显式选了渠道就用它, 选了 default 就跟随已安装包的渠道,
     * 已安装包渠道不明 (签名不匹配) 时归为正式版。
     */
    val checkVariant: AppVariant
        get() = when (updateToVariant) {
            "official_version" -> AppVariant.OFFICIAL
            "beta_release_version" -> AppVariant.BETA_RELEASE
            "beta_releaseA_version" -> AppVariant.BETA_RELEASEA
            else ->
                if (currentAppVariant == AppVariant.UNKNOWN) AppVariant.OFFICIAL
                else currentAppVariant
        }
}

/**
 * 检测到的新版本 (形态足够容纳"直链下载"与"跳商店"两类渠道)。
 *
 * @param versionName  新版本号
 * @param releaseNote  更新说明 (markdown)
 * @param downloadUrl  本平台安装包直链; 商店渠道为空串
 * @param fileName     安装包文件名; 商店渠道为空串
 * @param landingUrl   跳转目标: release 页 / App Store 页 / 商店 scheme (如 `market://`、`store://`)
 */
data class UpdateCheckInfo(
    val versionName: String,
    val releaseNote: String,
    val downloadUrl: String = "",
    val fileName: String = "",
    val landingUrl: String = "",
) {
    val hasAsset: Boolean get() = downloadUrl.isNotBlank()
}

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class NewVersion(val info: UpdateCheckInfo) : UpdateCheckResult
    data class Failed(val error: Throwable) : UpdateCheckResult
}
