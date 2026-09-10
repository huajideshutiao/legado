package io.legado.app.help.update

import io.legado.app.constant.AppConst
import io.legado.app.constant.appInfo
import io.legado.app.help.config.AppConfig

/**
 * 安卓端更新环境注册 (薄壳转发 shared)。
 *
 * 检查更新全链路在 shared: 关于页 → [AppUpdateManager.check] → [UpdateCheckers] 取检测器
 * ([GitHubReleaseChecker]) → 有新版本弹 updateDialog Overlay
 * ([io.legado.app.ui.about.UpdateDialogOverlayContent], 四端同一个弹窗) → 下载走
 * [io.legado.app.model.Download.start] → DownloadService (系统 DownloadManager,
 * 下载完成后 openFileUri 拉起安装器, 对齐原版)。
 *
 * 原 app 端 `AppUpdate` object (WaitDialog + 自己 showOverlay) 已删: 等待态由关于页
 * checkingUpdate 置灰入口承担, 弹窗与检测都在 shared, 不再需要 Android 专属分支。
 */
fun registerAndroidAppUpdate() {
    AppUpdateManager.register(AndroidUpdateEnvironment)
}

/** 安卓端运行时信息 (版本号/渠道/ABI 全走 [AppConst.appInfo])。 */
private object AndroidUpdateEnvironment : AppUpdateEnvironment {
    override val platform: UpdatePlatform get() = UpdatePlatform.ANDROID
    override val currentVersionName: String get() = AppConst.appInfo.versionName
    override val currentAppVariant: AppVariant get() = AppConst.appInfo.appVariant
    override val supportedAbis: List<String> get() = android.os.Build.SUPPORTED_ABIS.toList()
    override val updateToVariant: String get() = AppConfig.updateToVariant ?: "default_version"
}
