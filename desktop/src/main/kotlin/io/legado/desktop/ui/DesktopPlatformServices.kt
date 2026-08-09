package io.legado.desktop.ui

import io.legado.app.constant.AppLog
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.help.toast.DesktopTrayNotifier
import io.legado.app.ui.root.BrowserService
import io.legado.app.ui.root.CrashLogProvider
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
import io.legado.desktop.help.DesktopCrashLogDirs
import io.legado.desktop.help.DesktopKeepAwake
import io.legado.desktop.ui.component.FileDialogs
import java.awt.Desktop
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
 * Window 全屏经 [DesktopFullscreenController] 切 Windows 真全屏 (无边框独占窗口覆盖任务栏),
 * 非 Windows 平台显式日志暂不支持 (不做 AWT fallback); Orientation/SystemBars 桌面端无对应概念为 no-op。
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
    override val crashLogs: CrashLogProvider = DesktopCrashLogProvider()
}

// Windows 走 COM IFileDialog 现代对话框, macOS/Linux 走 AWT (统一入口 FileDialogs)
private class DesktopFilePickerService : FilePickerService {
    override fun pickFile(filter: FileFilter): String? =
        FileDialogs.pickOpenFile(extensions = filter.extensions)?.absolutePath

    override fun pickFiles(filter: FileFilter): List<String> =
        // Windows IFileDialog 原生多选; macOS/Linux AWT FileDialog 多选模式
        FileDialogs.pickOpenFiles(extensions = filter.extensions).map { it.absolutePath }

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

// shareText 走系统剪贴板; shareFile 用系统文件管理器定位文件
private class DesktopShareService : ShareService {
    override fun shareText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun shareFile(filePath: String, mimeType: String) {
        val file = File(filePath)
        if (!file.isFile) {
            AppLog.put("DesktopShareService.shareFile: 文件不存在: " + filePath)
            return
        }
        runCatching {
            val absolutePath = file.absolutePath
            val osName = System.getProperty("os.name").lowercase()
            when {
                osName.contains("win") ->
                    Runtime.getRuntime().exec(arrayOf("explorer", "/select,", absolutePath))

                osName.contains("mac") ->
                    Runtime.getRuntime().exec(arrayOf("open", "-R", absolutePath))

                else ->
                    Runtime.getRuntime().exec(arrayOf("xdg-open", file.parentFile.absolutePath))
            }
        }.onFailure {
            AppLog.put("DesktopShareService.shareFile: 打开文件位置失败: " + it.localizedMessage)
        }
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

// Window 全屏: Windows 走原生 HWND 真全屏 (无边框独占窗口覆盖任务栏, 经
// DesktopFullscreenController); 非 Windows 平台显式日志暂不支持; 仅供 F11 手动切换;
// 常亮走 DesktopKeepAwake (阻止系统休眠); 方向/系统栏桌面端无对应概念, no-op
private class DesktopWindowController(
    private val handle: DesktopWindowHandle,
) : WindowController {
    // 路由策略的 fullscreen 是 Android 沉浸式隐藏系统栏语义, 桌面无系统栏, 不该据此改窗口尺寸
    override val appliesPolicyFullscreen: Boolean get() = false

    override fun setFullscreen(enabled: Boolean) {
        val window = handle.window ?: return
        // 操作结果同步到全局状态: 成功才翻转 DesktopWindowChrome.fullscreen,
        // 自绘控制栏据此隐藏/显示 + 菜单勾选态; F11 (AppGlobalShortcuts) 与控制栏
        // 菜单共用本入口, 单点同步防两处状态分叉
        val ok = DesktopFullscreenController.setFullscreen(window, enabled)
        if (ok) DesktopWindowChrome.fullscreen = enabled
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        // 对照 app 端 FLAG_KEEP_SCREEN_ON: 阻止系统休眠/息屏 (Win=SetThreadExecutionState,
        // mac=caffeinate, Linux=systemd-inhibit), 见 DesktopKeepAwake
        DesktopKeepAwake.setKeepScreenOn(enabled)
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

// 媒体播放: 已核实 commonMain 无调用方 (MediaService 接口仅声明, 桌面端朗读/音频走
// 独立的 TtsEngineProvider / DesktopHttpTtsPlayer), 保持 no-op, 待音频路由接入时再接线
private class DesktopMediaService : MediaService {
    override fun playMedia(url: String, headers: Map<String, String>) {
        // TODO: 待接入桌面媒体播放 (当前无调用方)
    }

    override fun pauseMedia() = Unit
    override fun stopMedia() = Unit
}

// 通知: 经 DesktopTrayNotifier 委托宿主托盘图标显示气泡 (与 toast 同通道),
// 托盘未注册 (无头 / 无托盘) 时落 stdout; 气泡由系统自动超时消失, cancel 为 no-op
private class DesktopNotificationService : NotificationService {
    override fun notify(id: Int, title: String, content: String) {
        val message = "$title | $content"
        val sent = runCatching {
            DesktopTrayNotifier.sender?.invoke(message) == true
        }.getOrDefault(false)
        if (!sent) println("[notification] $message")
    }

    override fun cancelNotification(id: Int) = Unit
}

// 外部请求: desktop 启动请求经 Main.kt handleDeepLinkArgs 处理, 此处返回 null/false
private class DesktopExternalRequestService : ExternalRequestService {
    override fun parseLaunchRequest(request: Any): LaunchRequest? = null
    override fun handleLaunchRequest(request: LaunchRequest): Boolean = false
}

// 桌面端崩溃日志提供者: 读 DesktopCrashHandler 落盘的两个目录 (缓存 + 用户导出),
// 对照 app 端 CrashViewModel.initData 同样从外部缓存 + 备份路径两处收集
private class DesktopCrashLogProvider : CrashLogProvider {

    private fun dirs(): List<File> = DesktopCrashLogDirs.readDirs()

    /** 同名文件优先取先出现的目录 (writeDirs 顺序即优先级)。 */
    private fun findFile(name: String): File? =
        dirs().map { File(it, name) }.firstOrNull { it.isFile }

    override suspend fun loadCrashLogs(): List<CrashLogProvider.CrashLogEntry> {
        return dirs()
            .filter { it.isDirectory }
            .flatMap { it.listFiles { f -> f.isFile }?.toList().orEmpty() }
            .sortedByDescending { it.name }
            .distinctBy { it.name }
            .map { CrashLogProvider.CrashLogEntry(it.name) }
    }

    override suspend fun readCrashLog(name: String): String? = findFile(name)?.readText()

    override suspend fun clearCrashLogs() {
        dirs().forEach { dir ->
            if (dir.isDirectory) dir.listFiles()?.forEach { it.delete() }
        }
    }

    override fun shareCrashLog(name: String) {
        // desktop 无系统分享面板, 改为打开文件所在目录并选中
        val file = findFile(name)
        if (file != null) {
            kotlin.runCatching {
                // 跨平台打开文件管理器并选中文件 (Windows: explorer /select; macOS: open -R; Linux: xdg-open)
                val osName = System.getProperty("os.name").lowercase()
                when {
                    osName.contains("win") -> {
                        Runtime.getRuntime()
                            .exec(arrayOf("explorer", "/select,", file.absolutePath))
                    }

                    osName.contains("mac") -> {
                        Runtime.getRuntime().exec(arrayOf("open", "-R", file.absolutePath))
                    }

                    else -> {
                        // Linux: 打开文件所在目录
                        Runtime.getRuntime().exec(arrayOf("xdg-open", file.parent))
                    }
                }
            }.onFailure {
                AppLog.put("DesktopCrashLogProvider.shareCrashLog: 打开文件失败: ${it.localizedMessage}")
            }
        }
    }
}
