package io.legado.app.ui.root

import kotlin.concurrent.Volatile

/**
 * 平台服务聚合入口。每项能力保持小接口、单一职责，避免成为 God Interface。
 * 平台入口在系统启动时注册具体实现。
 */
interface PlatformServices {
    val capabilities: PlatformCapabilities
    val files: FilePickerService
    val sharing: ShareService
    val browser: BrowserService
    val permissions: PermissionService
    val window: WindowController
    val keyboard: KeyboardController
    val media: MediaService
    val notifications: NotificationService
    val externalRequests: ExternalRequestService
    val crashLogs: CrashLogProvider
}

object PlatformServiceProviders {
    @Volatile
    private var impl: PlatformServices? = null

    fun register(services: PlatformServices) {
        impl = services
    }

    fun get(): PlatformServices = impl
        ?: error("PlatformServices must be registered by the system entry")

    fun getOrNull(): PlatformServices? = impl
}

/** 文件选择器：单选/多选/保存。 */
interface FilePickerService {
    fun pickFile(filter: FileFilter): String?
    fun pickFiles(filter: FileFilter): List<String>
    fun saveFile(suggestedName: String, defaultDir: String? = null): String?

    /**
     * 保存图片字节到用户选择的位置（图片查看器长按保存，对照 master PhotoDialog.doSaveImage）。
     * 各端: app=SAF CreateDocument → contentResolver 写入; desktop=保存对话框 → File 写入;
     * 未实现返回 false（调用方 toast 失败）。
     */
    fun saveImageBytes(suggestedName: String, bytes: ByteArray): Boolean = false

    // 选目录 (对照 app 端 HandleFileContract.DIR_SYS / OpenDocumentTree),
    // 各端按需实现, 默认返回 null 由调用方降级
    fun pickDirectory(): String? = null

    /**
     * 备份目录可写性预检 (对照 app 端 BackupConfigFragment.backup 的 FileDoc.checkWrite)。
     * Android SAF content:// 目录用 DocumentFile 判断可写性, 不可写返回 false 由调用方引导重新选目录;
     * 无 SAF 概念的平台 (桌面等普通路径) 默认视为可写。
     */
    fun checkWrite(path: String): Boolean = true
}

/** 分享：文本与文件。 */
interface ShareService {
    fun shareText(text: String)
    fun shareFile(filePath: String, mimeType: String)
}

/** 浏览器：外链与内置页。 */
interface BrowserService {
    fun openUrl(url: String)
    fun openUrlInApp(url: String)
}

/** 权限：查询与请求。 */
interface PermissionService {
    fun hasPermission(permission: String): Boolean
    fun requestPermission(permission: String): Boolean
}

/** 窗口控制：全屏/常亮/方向/系统栏。 */
interface WindowController {
    /**
     * 路由策略里的 fullscreen 是"沉浸式隐藏系统栏"语义 (对照 app 端 Activity.fullScreen)。
     * 无系统栏的平台 (desktop) 置 false, 避免被当成"窗口最大化"; 手动全屏 (F11) 不受影响。
     */
    val appliesPolicyFullscreen: Boolean get() = true

    fun setFullscreen(enabled: Boolean)
    fun setKeepScreenOn(enabled: Boolean)
    fun setOrientation(policy: OrientationPolicy)
    fun setSystemBars(policy: SystemBarsPolicy)
}

/** 软输入法控制（hide/show/resize）。 */
interface KeyboardController {
    fun hideSoftInput()
    fun showSoftInput()
    fun setSoftInputPolicy(policy: SoftInputPolicy)
}

/** 媒体播放控制。 */
interface MediaService {
    fun playMedia(url: String, headers: Map<String, String>)
    fun pauseMedia()
    fun stopMedia()
}

/** 通知：发送与取消。 */
interface NotificationService {
    fun notify(id: Int, title: String, content: String)
    fun cancelNotification(id: Int)
}

/** 外部请求：解析与处理启动请求。 */
interface ExternalRequestService {
    fun parseLaunchRequest(request: Any): LaunchRequest?
    fun handleLaunchRequest(request: LaunchRequest): Boolean
}

/**
 * 崩溃日志提供者：读取崩溃日志文件列表、读文件内容、清空、分享。
 *
 * 对照 app 端 CrashLogsDialog.CrashViewModel 的逻辑:
 * - initData: 从 externalCacheDir/crash 和备份路径收集崩溃日志文件
 * - readFile: 读取单个崩溃日志文件内容
 * - clearCrashLog: 删除所有崩溃日志文件
 * - 分享: 通过 ShareService 分享文件
 *
 * 各端 actual 实现:
 * - androidMain: 外部缓存目录 + SAF 备份路径
 * - desktopMain: 用户目录/logs/crash
 * - iOS/鸿蒙: 暂返回空列表 (后续按需实现)
 */
interface CrashLogProvider {
    /** 崩溃日志条目 (仅文件名, 与 CrashLogItem 一致) */
    data class CrashLogEntry(val name: String)

    /** 收集崩溃日志文件列表 (对照 CrashViewModel.initData) */
    suspend fun loadCrashLogs(): List<CrashLogEntry>

    /** 读取单个崩溃日志文件内容 (对照 CrashViewModel.readFile) */
    suspend fun readCrashLog(name: String): String?

    /** 清空所有崩溃日志 (对照 CrashViewModel.clearCrashLog) */
    suspend fun clearCrashLogs()

    /** 分享单个崩溃日志文件 (对照 CrashLogsDialog.shareFile) */
    fun shareCrashLog(name: String)
}

/** 文件类型过滤：MIME 与扩展名任一命中即接受。 */
data class FileFilter(
    val mimeTypes: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
) {
    companion object {
        val Any = FileFilter()
        val Images = FileFilter(
            mimeTypes = listOf("image/*"),
            extensions = listOf("png", "jpg", "jpeg", "gif", "webp", "bmp"),
        )
        val Text = FileFilter(
            mimeTypes = listOf("text/*"),
            extensions = listOf("txt", "log", "md", "json", "xml"),
        )
    }
}

enum class OrientationPolicy { Unspecified, Portrait, Landscape, Sensor, ReversePortrait }

enum class SystemBarsPolicy {
    Default, Hidden, Visible, Immersive,

    /** 仅隐藏状态栏（阅读页 hideStatusBar 独立配置） */
    HiddenStatusBar,

    /** 仅隐藏导航栏（阅读页 hideNavigationBar 独立配置） */
    HiddenNavigationBar,
}

enum class SoftInputPolicy { Default, Resize, Pan, Hidden }
