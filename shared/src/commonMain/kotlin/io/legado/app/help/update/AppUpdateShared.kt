package io.legado.app.help.update

import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope

/**
 * app 端"检查更新"入口 (纯逻辑, 下沉 commonMain)。
 *
 * 检测与执行已拆成两层: 检测走 [UpdateChecker] (当前四端共用 [GitHubReleaseChecker]),
 * 执行走 [UpdateExecutor] / [UpdateAction], 组合关系见 [UpdateStrategies]。
 * 本对象只保留 app 端历史签名 (Coroutine + [AbiProvider]), 内部委托新分层;
 * desktop/iOS/鸿蒙统一走 [AppUpdateManager]。
 */
object AppUpdateShared {

    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String,
        /** release 页面地址, 无本平台产物时用于降级跳转。 */
        val releasePageUrl: String = "",
    )

    fun getCheckVariant(
        updateToVariant: String,
        currentAppVariant: AppVariant
    ): AppVariant {
        return when (updateToVariant) {
            "official_version" -> AppVariant.OFFICIAL
            "beta_release_version" -> AppVariant.BETA_RELEASE
            "beta_releaseA_version" -> AppVariant.BETA_RELEASEA
            else -> {
                if (currentAppVariant == AppVariant.UNKNOWN) AppVariant.OFFICIAL else currentAppVariant
            }
        }
    }

    suspend fun getLatestRelease(checkVariant: AppVariant): List<AppReleaseInfo> =
        GitHubReleaseChecker().fetchRelease(checkVariant).gitReleaseToAppReleaseInfo()

    /** app 端入口: 返回 null 表示已是最新版, 失败抛异常 (由 onError 分支处理)。 */
    fun check(
        scope: CoroutineScope,
        abiProvider: AbiProvider,
        updateToVariant: String,
        currentVersionName: String,
        currentAppVariant: AppVariant
    ): Coroutine<UpdateInfo?> {
        return Coroutine.async(scope) {
            val result = UpdateStrategies.of(UpdatePlatform.ANDROID).checker.check(
                UpdateCheckRequest(
                    platform = UpdatePlatform.ANDROID,
                    currentVersionName = currentVersionName,
                    currentAppVariant = currentAppVariant,
                    updateToVariant = updateToVariant,
                    supportedAbis = abiProvider.supportedAbis.toList(),
                )
            )
            when (result) {
                // 无匹配 apk 时返回 null (等同旧实现 minByOrNull 落空 → "已是最新版本"),
                // app 端 UpdateDialog 的下载按钮依赖非空直链
                is UpdateCheckResult.NewVersion ->
                    result.info.takeIf { it.hasAsset }?.toUpdateInfo()

                is UpdateCheckResult.Failed -> throw result.error
                UpdateCheckResult.UpToDate -> null
            }
        }.timeout(10000)
    }
}

fun UpdateCheckInfo.toUpdateInfo(): AppUpdateShared.UpdateInfo = AppUpdateShared.UpdateInfo(
    tagName = versionName,
    updateLog = releaseNote,
    downloadUrl = downloadUrl,
    fileName = fileName,
    releasePageUrl = landingUrl,
)
