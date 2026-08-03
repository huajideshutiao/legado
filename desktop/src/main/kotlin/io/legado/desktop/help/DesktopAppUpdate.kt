package io.legado.desktop.help

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.help.update.AbiTokens
import io.legado.app.help.update.AppUpdateEnvironment
import io.legado.app.help.update.AppUpdateManager
import io.legado.app.help.update.UpdateAction
import io.legado.app.help.update.UpdateCheckInfo
import io.legado.app.help.update.UpdateExecutor
import io.legado.app.help.update.UpdateExecutors
import io.legado.app.help.update.UpdatePlatform
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.desktop.constant.DesktopAppInfo

/**
 * 桌面端更新能力注册 (薄壳转发 shared, 不再自写 GitHub API 解析)。
 *
 * 检查更新全链路在 shared: 关于页 [io.legado.app.ui.about.AboutScreenModel] →
 * [AppUpdateManager.check] → [io.legado.app.help.update.UpdateStrategies] 策略
 * (updateUrl 非空时换 [io.legado.app.help.update.CustomUrlUpdateChecker],
 * 为空回退 GitHubReleaseChecker, 与 app 端一致)。
 *
 * 本文件只注册两样东西:
 * 1. [AppUpdateEnvironment]: 平台/版本号/架构/自建更新源 (读 `PreferKey.updateUrl`)
 * 2. [UpdateExecutor]: 桌面端无 DownloadManager, 一律用系统浏览器打开
 *    下载直链 (有资产) 或 release 页 (无资产), 与旧 DesktopAppUpdate 手动下载行为一致
 *
 * 注册后 shared 关于页的"检查更新"入口自动出现 (AboutRoute 以
 * [AppUpdateManager.isAvailable] 为 gate), 新版本弹 [UpdateAvailableDialog]。
 */
fun registerDesktopAppUpdate() {
    AppUpdateManager.register(DesktopUpdateEnvironment())
    UpdateExecutors.register(DesktopUpdateExecutor)
}

/** 桌面端运行时信息。 */
private class DesktopUpdateEnvironment : AppUpdateEnvironment {
    override val platform: UpdatePlatform get() = currentPlatform()
    override val currentVersionName: String get() = DesktopAppInfo.versionName
    override val supportedAbis: List<String>
        get() = listOf(AbiTokens.normalize(System.getProperty("os.arch", "amd64")))
    override val updateUrl: String
        get() = PreferenceProviders.get().getString(PreferKey.updateUrl, "")
}

/** 桌面端执行器: 浏览器打开下载直链, 无资产降级打开 release 页。 */
private object DesktopUpdateExecutor : UpdateExecutor {
    override suspend fun execute(action: UpdateAction, info: UpdateCheckInfo): Boolean {
        // 有资产: 打开安装包直链; 无资产: 打开 release 页 (landingUrl)
        val url = info.downloadUrl.ifBlank { info.landingUrl }
        if (url.isBlank()) return false
        val browser = PlatformServiceProviders.getOrNull()?.browser ?: return false
        Toasters.get().toast("正在打开下载页")
        browser.openUrl(url)
        return true
    }
}

/** 按当前 OS 选更新平台 (决定 release 资产后缀匹配: msi/exe / dmg / deb-rpm)。 */
private fun currentPlatform(): UpdatePlatform {
    val os = System.getProperty("os.name", "").lowercase()
    return when {
        os.contains("win") -> UpdatePlatform.WINDOWS
        os.contains("mac") -> UpdatePlatform.MACOS
        else -> UpdatePlatform.LINUX
    }
}
