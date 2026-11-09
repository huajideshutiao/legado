package io.legado.desktop.help

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.update.AbiTokens
import io.legado.app.help.update.AppUpdateEnvironment
import io.legado.app.help.update.AppUpdateManager
import io.legado.app.help.update.UpdatePlatform
import io.legado.desktop.constant.DesktopAppInfo

/**
 * 桌面端更新能力注册 (薄壳转发 shared, 不再自写 GitHub API 解析)。
 *
 * 检查更新全链路在 shared: 关于页 [io.legado.app.ui.about.AboutScreenModel] →
 * [AppUpdateManager.check] → [io.legado.app.help.update.UpdateCheckers] 取检测器
 * ([io.legado.app.help.update.GitHubReleaseChecker])。有新版本时弹的是与 app 端
 * 同一个 updateDialog Overlay ([io.legado.app.ui.about.UpdateDialogOverlayContent]),
 * 下载按钮走 [io.legado.app.model.Download.start] → jvm ServiceLauncher →
 * FileDownloader 落 `{desktopAppRootDir}/downloads`。
 *
 * 本文件只注册 [AppUpdateEnvironment] (平台/版本号/架构); 注册后 shared 关于页的
 * "检查更新"入口自动出现 (AboutRoute 以 [AppUpdateManager.isAvailable] 为 gate)。
 */
fun registerDesktopAppUpdate() {
    AppUpdateManager.register(DesktopUpdateEnvironment())
}

/** 桌面端运行时信息。 */
private class DesktopUpdateEnvironment : AppUpdateEnvironment {
    override val platform: UpdatePlatform get() = currentPlatform()
    override val currentVersionName: String get() = DesktopAppInfo.versionName
    override val supportedAbis: List<String>
        get() = listOf(AbiTokens.normalize(System.getProperty("os.arch", "amd64")))

    /**
     * 更新渠道偏好 (其它设置 → 检查更新查找版本)。不读它则该设置项在桌面端形同虚设:
     * 恒查 /releases/latest, 选了"测试版"也拿不到 beta tag 里的 msi/zip/dmg/deb。
     *
     * 桌面端无签名校验, [currentAppVariant] 恒为 OFFICIAL, 所以 "default_version"
     * (跟随已装包渠道) 落在正式版 —— 与安卓端 default 语义一致。
     */
    override val updateToVariant: String
        get() = PreferenceProviders.get().getString(PreferKey.updateToVariant, "default_version")
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
