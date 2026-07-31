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

    // 选目录 (对照 app 端 HandleFileContract.DIR_SYS / OpenDocumentTree),
    // 各端按需实现, 默认返回 null 由调用方降级
    fun pickDirectory(): String? = null
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

enum class OrientationPolicy { Unspecified, Portrait, Landscape, Sensor }

enum class SystemBarsPolicy { Default, Hidden, Visible, Immersive }

enum class SoftInputPolicy { Default, Resize, Pan, Hidden }
