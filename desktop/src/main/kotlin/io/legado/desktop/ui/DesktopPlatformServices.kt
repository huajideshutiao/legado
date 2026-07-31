package io.legado.desktop.ui

import io.legado.app.constant.AppLog
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.ui.root.BrowserService
import io.legado.app.ui.root.ExternalRequestService
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.FilePickerService
import io.legado.app.ui.root.KeyboardController
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.MediaService
import io.legado.app.ui.root.NotificationService
import io.legado.app.ui.root.OrientationPolicy
import io.legado.app.ui.root.PermissionService
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.PlatformServices
import io.legado.app.ui.root.ShareService
import io.legado.app.ui.root.SoftInputPolicy
import io.legado.app.ui.root.SystemBarsPolicy
import io.legado.app.ui.root.WindowController
import io.legado.desktop.ui.component.FileDialogs
import java.awt.Desktop
import java.awt.GraphicsDevice
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

/**
 * AWT 窗口句柄持有者: Main.kt 在 Compose Window 组装后注入 [window],
 * 供 [DesktopWindowController] 切换全屏等窗口策略。
 */
class DesktopWindowHandle {
    @Volatile
    var window: Window? = null
}

/**
 * desktop 端 [PlatformServices] 实现：聚合 10 项平台能力。
 *
 * 各子能力提供最小可用实现：FilePicker/Share/Browser 走 AWT/系统原生可用;
 * Window 全屏经 [GraphicsDevice] 切换; Orientation/SystemBars 桌面端无对应概念为 no-op。
 * 不伪造行为, 无法实现的返回空/no-op。
 */
class DesktopPlatformServices(
    private val capabilitiesImpl: PlatformCapabilities,
    val windowHandle: DesktopWindowHandle = DesktopWindowHandle(),
) : PlatformServices {

    override val capabilities: PlatformCapabilities get() = capabilitiesImpl
    override val files: FilePickerService = DesktopFilePickerService()
    override val sharing: ShareService = DesktopShareService()
    override val browser: BrowserService = DesktopBrowserService()
    override val permissions: PermissionService = DesktopPermissionService()
    override val window: WindowController = DesktopWindowController(windowHandle)
    override val keyboard: KeyboardController = DesktopKeyboardController()
    override val media: MediaService = DesktopMediaService()
    override val notifications: NotificationService = DesktopNotificationService()
    override val externalRequests: ExternalRequestService = DesktopExternalRequestService()
}

// Windows 走 COM IFileDialog 现代对话框, macOS/Linux 走 AWT (统一入口 FileDialogs)
private class DesktopFilePickerService : FilePickerService {
    override fun pickFile(filter: FileFilter): String? =
        FileDialogs.pickOpenFile(extensions = filter.extensions)?.absolutePath

    override fun pickFiles(filter: FileFilter): List<String> =
        // 多选暂用单选兜底 (FileDialogs 已具备多选能力, 待调用方需要时再放开)
        listOfNotNull(pickFile(filter))

    // 调用方没给目录时起始目录用用户可见产物目录 (桌面/legado), 别落在应用数据目录
    override fun saveFile(suggestedName: String, defaultDir: String?): String? =
        FileDialogs.pickSaveFile(
            defaultName = suggestedName,
            initialDir = (defaultDir ?: userExportDir())?.let(::File)?.takeIf { it.isDirectory },
        )?.absolutePath

    override fun pickDirectory(): String? =
        FileDialogs.pickDirectory(
            initialDir = userExportDir()?.let(::File)?.takeIf { it.isDirectory },
        )?.absolutePath
}

/** 用户可见产物目录 (不存在则创建), 取不到返回 null 让对话框用系统默认起始目录。 */
private fun userExportDir(): String? = runCatching {
    val dir = File(DataStorageProviders.get().userExportDir)
    if (dir.isDirectory || dir.mkdirs()) dir.absolutePath else null
}.getOrNull()

// shareText 走系统剪贴板; shareFile 桌面端无系统分享面板, no-op + TODO
private class DesktopShareService : ShareService {
    override fun shareText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun shareFile(filePath: String, mimeType: String) {
        // TODO: desktop 无系统分享面板, 暂 no-op
        AppLog.put("DesktopShareService.shareFile: desktop 无系统分享面板, 已忽略: $filePath")
    }
}

// openUrl 走 Desktop.browse; openUrlInApp 无内置 WebView, 降级走系统浏览器
private class DesktopBrowserService : BrowserService {
    override fun openUrl(url: String) {
        if (Desktop.isDesktopSupported()) {
            runCatching { Desktop.getDesktop().browse(URI(url)) }
                .onFailure { AppLog.put("DesktopBrowserService.openUrl 失败: ${it.localizedMessage}") }
        }
    }

    override fun openUrlInApp(url: String) = openUrl(url)
}

// desktop 无权限模型, hasPermission 恒真 (无权限即授予); requestPermission 无需请求直接成功
private class DesktopPermissionService : PermissionService {
    override fun hasPermission(permission: String): Boolean = true
    override fun requestPermission(permission: String): Boolean = true
}

// Window 全屏经 AWT GraphicsDevice 切换 (javafx Stage.setFullScreen 等价物);
// 常亮/方向/系统栏桌面端无对应概念, no-op
private class DesktopWindowController(
    private val handle: DesktopWindowHandle,
) : WindowController {
    override fun setFullscreen(enabled: Boolean) {
        val window = handle.window ?: return
        val device = window.graphicsConfiguration?.device ?: return
        // 进入全屏: 设为全屏窗口; 退出全屏: 传 null 释放
        device.setFullScreenWindow(if (enabled) window else null)
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        // desktop 无 KeepScreenOn 概念 (常亮由电源管理控制), no-op
    }

    override fun setOrientation(policy: OrientationPolicy) {
        // desktop 无屏幕方向概念, no-op
    }

    override fun setSystemBars(policy: SystemBarsPolicy) {
        // desktop 无系统栏 (状态栏/导航栏), no-op
    }
}

// 软输入法: desktop 无软键盘, no-op
private class DesktopKeyboardController : KeyboardController {
    override fun hideSoftInput() = Unit
    override fun showSoftInput() = Unit
    override fun setSoftInputPolicy(policy: SoftInputPolicy) = Unit
}

// 媒体播放: 桌面端 MediaService 暂未接入, no-op + TODO
private class DesktopMediaService : MediaService {
    override fun playMedia(url: String, headers: Map<String, String>) {
        // TODO: 待接入桌面媒体播放
    }

    override fun pauseMedia() = Unit
    override fun stopMedia() = Unit
}

// 通知: 桌面端 SystemTray 通知由 registerDesktopNotificationProgress 单独承载, 此处 no-op + TODO
private class DesktopNotificationService : NotificationService {
    override fun notify(id: Int, title: String, content: String) {
        // TODO: 可接入 SystemTray 通知
    }

    override fun cancelNotification(id: Int) = Unit
}

// 外部请求: desktop 启动请求经 Main.kt handleDeepLinkArgs 处理, 此处返回 null/false
private class DesktopExternalRequestService : ExternalRequestService {
    override fun parseLaunchRequest(request: Any): LaunchRequest? = null
    override fun handleLaunchRequest(request: LaunchRequest): Boolean = false
}
