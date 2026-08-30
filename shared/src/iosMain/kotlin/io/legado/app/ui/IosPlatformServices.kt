@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui

import io.legado.app.constant.AppLog
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.file.pickDirectory as pickDirectoryDocument
import io.legado.app.help.log.NativeCrashLogs
import io.legado.app.help.openURL
import io.legado.app.help.topMostViewController
import io.legado.app.ui.root.BrowserService
import io.legado.app.ui.root.CrashLogProvider
import io.legado.app.ui.root.ExternalRequestService
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.FilePickerService
import io.legado.app.ui.root.KeyboardController
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.LaunchRequestBus
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
import io.legado.app.utils.File
import kotlin.concurrent.Volatile
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNotificationName
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.popoverPresentationController
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

// iOS 平台服务聚合: 文件选择/分享/浏览器走 UIKit, 权限走 Photos/AVCapture/UserNotifications,
// 媒体走 AVPlayer, 通知走 UNUserNotificationCenter, 外部请求走 URL Scheme 解析
object IosPlatformServices : PlatformServices {
    override val capabilities: PlatformCapabilities = IosPlatformCapabilities
    override val files: FilePickerService = IosFilePickerService
    override val sharing: ShareService = IosShareService
    override val browser: BrowserService = IosBrowserService
    override val permissions: PermissionService = IosPermissionService
    override val window: WindowController = IosWindowController
    override val keyboard: KeyboardController = IosKeyboardController
    override val media: MediaService = IosMediaService
    override val notifications: NotificationService = IosNotificationService
    override val externalRequests: ExternalRequestService = IosExternalRequestService

    // 崩溃日志: 从 {filesDir}/logs 收集 appLog-*.txt (NativeAppLogHost 在 recordLog 开启时落盘,
    // 落盘/读取实现与鸿蒙端共用 nativeMain 的 NativeCrashLogs)
    override val crashLogs: CrashLogProvider = object : CrashLogProvider {
        override suspend fun loadCrashLogs(): List<CrashLogProvider.CrashLogEntry> =
            NativeCrashLogs.listLogs().map { CrashLogProvider.CrashLogEntry(it) }

        override suspend fun readCrashLog(name: String): String? = NativeCrashLogs.readLog(name)

        override suspend fun clearCrashLogs() = NativeCrashLogs.clearLogs()

        // 系统分享面板分享日志文件 (对照 Android CrashLogsDialog.shareFile)
        override fun shareCrashLog(name: String) =
            IosShareService.shareFile(NativeCrashLogs.logPath(name), "text/plain")
    }
}

private object IosFilePickerService : FilePickerService {

    // suspend 选择器转同步 (模式同 KmpHttpTypes.ios.kt 的 KmpCall.execute):
    // 调用方均在 withContext(IoDispatcher) 内调用, 主线程调用会 deadlock
    override fun pickFile(filter: FileFilter): String? =
        runBlocking { pickDocuments(filter.toUtis()) }?.firstOrNull()?.path

    override fun pickFiles(filter: FileFilter): List<String> =
        runBlocking { pickDocuments(filter.toUtis(), allowsMultiple = true) }
            .orEmpty().mapNotNull { it.path }

    /**
     * iOS 无"先选路径后写入"的系统面板 (UIDocumentPicker 导出需先有文件),
     * 故返回沙盒 Documents 下的可写路径; Info.plist 开启文件共享后用户可在"文件" app 中取用。
     */
    override fun saveFile(suggestedName: String, defaultDir: String?): String? {
        val dir = defaultDir ?: (AppFilesDirs.get().filesDir + "/export")
        return runCatching {
            File(dir).mkdirs()
            "$dir/$suggestedName"
        }.getOrNull()
    }

    override fun saveImageBytes(suggestedName: String, bytes: ByteArray): Boolean? {
        val path = saveFile(suggestedName) ?: return null
        return runCatching {
            File(path).writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    override fun pickDirectory(): String? = runBlocking { pickDirectoryDocument() }?.path
}

// FileFilter 扩展名 → UTI (UIDocumentPicker 只认 UTI); 未知类型放行全部
private fun FileFilter.toUtis(): List<String> = when {
    extensions.isEmpty() -> listOf("public.item")
    else -> extensions.mapNotNull { ext ->
        when (ext.lowercase()) {
            "txt" -> "public.plain-text"
            "json" -> "public.json"
            "zip" -> "public.zip-archive"
            "epub" -> "org.idpf.epub-container"
            "png", "jpg", "jpeg", "gif", "webp" -> "public.image"
            "bmp" -> "public.bmp"
            else -> null
        }
    }.distinct().ifEmpty { listOf("public.item") }
}

// UIActivityViewController 系统分享面板 (对照 app 端 Intent.ACTION_SEND)
private object IosShareService : ShareService {
    override fun shareText(text: String) = presentShareSheet(listOf(text))

    override fun shareFile(filePath: String, mimeType: String) {
        presentShareSheet(listOf(NSURL.fileURLWithPath(filePath)))
    }
}

internal fun presentShareSheet(items: List<Any>) {
    dispatch_async(dispatch_get_main_queue()) {
        val vc = topMostViewController() ?: return@dispatch_async
        val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
        // iPad 需 popover anchor, 否则 present 崩溃
        sheet.popoverPresentationController?.sourceView = vc.view
        vc.presentViewController(sheet, animated = true, completion = null)
    }
}

// iOS 无内置 WebView 宿主可复用, 两者均走系统浏览器 (对照 desktop openUrlInApp 降级)
private object IosBrowserService : BrowserService {
    override fun openUrl(url: String) = openURL(url)
    override fun openUrlInApp(url: String) = openURL(url)
}

/**
 * iOS 运行时权限: 相册/相机/麦克风为同步查询, 通知只有异步 API 故缓存最近一次结果。
 * 沙盒内文件/网络等 iOS 无运行时权限概念, 一律视为已授予 (对照 desktop 恒 true)。
 */
private object IosPermissionService : PermissionService {

    /** 通知授权态缓存 (UNUserNotificationCenter 只提供异步查询, 每次调用顺带刷新)。 */
    @Volatile
    private var notificationGranted = false

    override fun hasPermission(permission: String): Boolean = when (permission.toIosPermission()) {
        IosPermission.Notification -> {
            refreshNotificationStatus()
            notificationGranted
        }

        IosPermission.Photos -> PHPhotoLibrary
            .authorizationStatusForAccessLevel(PHAccessLevelReadWrite)
            .let { it == PHAuthorizationStatusAuthorized || it == PHAuthorizationStatusLimited }

        IosPermission.Camera ->
            AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) ==
                AVAuthorizationStatusAuthorized

        IosPermission.Microphone ->
            AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio) ==
                AVAuthorizationStatusAuthorized

        null -> true
    }

    // iOS 授权弹窗恒异步返回, 返回值仅表示已成功发起请求 (语义对照 Android requestPermissions)
    override fun requestPermission(permission: String): Boolean {
        val target = permission.toIosPermission() ?: return true
        return runCatching {
            when (target) {
                IosPermission.Notification -> UNUserNotificationCenter
                    .currentNotificationCenter()
                    .requestAuthorizationWithOptions(
                        UNAuthorizationOptionAlert or UNAuthorizationOptionSound
                    ) { granted, _ -> notificationGranted = granted }
                IosPermission.Photos -> PHPhotoLibrary
                    .requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { }

                IosPermission.Camera ->
                    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { }

                IosPermission.Microphone ->
                    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeAudio) { }
            }
            true
        }.getOrElse {
            AppLog.put("IosPermissionService.requestPermission 失败: ${it.message}")
            false
        }
    }

    private fun refreshNotificationStatus() {
        runCatching {
            UNUserNotificationCenter.currentNotificationCenter()
                .getNotificationSettingsWithCompletionHandler { settings ->
                    val status = settings?.authorizationStatus
                    notificationGranted = status == UNAuthorizationStatusAuthorized ||
                        status == UNAuthorizationStatusProvisional
                }
        }
    }
}

/** 权限名归一 (调用方沿用 Android 常量字符串); null = iOS 无对应运行时权限。 */
private enum class IosPermission { Notification, Photos, Camera, Microphone }

private fun String.toIosPermission(): IosPermission? {
    val key = uppercase()
    return when {
        key.contains("NOTIFICATION") -> IosPermission.Notification
        key.contains("PHOTO") || key.contains("MEDIA_IMAGES") -> IosPermission.Photos
        key.contains("CAMERA") -> IosPermission.Camera
        key.contains("RECORD_AUDIO") || key.contains("MICROPHONE") -> IosPermission.Microphone
        else -> null
    }
}

// UIKit 窗口控制: KeepScreenOn 经 UIApplication.isIdleTimerDisabled (对照 Android FLAG_KEEP_SCREEN_ON);
// 状态栏显隐由 SwiftUI 根视图 .statusBarHidden 控制 (iOS 13+, SwiftUI 下 VC 级 prefersStatusBarHidden
// 不生效, 见 iosApp/iOSApp.swift 的 onReceive 监听), 经 NSNotificationCenter 桥接;
// 方向 iOS 不支持编程强制, 均为 no-op

/** 状态栏显隐通知名 (与 iosApp/iOSApp.swift 的 Notification.Name 一致)。 */
internal const val IosStatusBarHiddenNotification = "legado.statusBarHidden"

/** 通知 userInfo 中显隐布尔值的 key。 */
internal const val IosStatusBarHiddenKey = "hidden"

private object IosWindowController : WindowController {
    override fun setFullscreen(enabled: Boolean) {
        // iOS 全屏等价于隐藏状态栏, 由 setSystemBars 统一承担 (此处不重复)
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        // true=禁止熄屏; 阅读/音频/视频路由保持常亮
        UIApplication.sharedApplication().idleTimerDisabled = enabled
    }

    override fun setOrientation(policy: OrientationPolicy) {
        // iOS 不支持编程强制方向 (UIDevice.setValue 已弃用且受 Info.plist 限制), no-op
    }

    override fun setSystemBars(policy: SystemBarsPolicy) {
        // 状态栏显隐经 NSNotificationCenter 桥接到 SwiftUI 根视图 .statusBarHidden
        // (iOSApp.swift onReceive 监听后应用; SwiftUI 宿主下 VC 的 prefersStatusBarHidden 不生效,
        // .statusBarHidden modifier 是唯一可靠途径)。iOS 无导航栏概念 (home indicator 不占内容区,
        // 由 safeAreaInsets 驱动), 故 HiddenNavigationBar 无对应动作。
        val hidden = policy == SystemBarsPolicy.Hidden || policy == SystemBarsPolicy.HiddenStatusBar
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = IosStatusBarHiddenNotification,
            `object` = null,
            userInfo = mapOf(IosStatusBarHiddenKey to hidden),
        )
    }
}

// 软输入法: 收起走 UIApplication.sendAction(resignFirstResponder) (无需持有具体 responder);
// 唤起须由 UITextField.becomeFirstResponder 触发, 无公开全局 API
private object IosKeyboardController : KeyboardController {
    override fun hideSoftInput() {
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication().sendAction(
                NSSelectorFromString("resignFirstResponder"),
                to = null,
                from = null,
                forEvent = null,
            )
        }
    }

    override fun showSoftInput() {
        // iOS 无强制唤起键盘的公开 API, no-op
    }

    override fun setSoftInputPolicy(policy: SoftInputPolicy) {
        // iOS 键盘避让由系统 + Compose 自动处理, 仅 Hidden 需要主动收起
        if (policy == SoftInputPolicy.Hidden) hideSoftInput()
    }
}

// 通用媒体播放: AVPlayer + AVURLAsset (模式同 IosHttpTtsPlayer),
// 独立实例避免抢占音频书/HttpTTS/视频书播放器
private object IosMediaService : MediaService {

    @Volatile
    private var player: AVPlayer? = null

    override fun playMedia(url: String, headers: Map<String, String>) {
        val nsUrl = NSURL.URLWithString(url) ?: run {
            AppLog.put("IosMediaService.playMedia 非法 URL: $url")
            return
        }
        // 请求头经 AVURLAssetHTTPHeaderFieldsKey 注入 (该常量未随平台库暴露, 用同名字面量)
        val options: Map<Any?, Any?>? = if (headers.isEmpty()) {
            null
        } else {
            mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers)
        }
        stopMedia()
        val item = AVPlayerItem(asset = AVURLAsset(nsUrl, options))
        player = AVPlayer(playerItem = item).also { it.play() }
    }

    override fun pauseMedia() {
        player?.pause()
    }

    override fun stopMedia() {
        player?.let {
            it.pause()
            it.replaceCurrentItemWithPlayerItem(null)
        }
        player = null
    }
}

// 通知: UNUserNotificationCenter 本地通知 (授权已在 registerIosNotificationProgress 请求过);
// id 转固定 identifier 复用同一条 (对照 Android NotificationManager.notify(id, ...))
private object IosNotificationService : NotificationService {

    override fun notify(id: Int, title: String, content: String) {
        val identifier = identifierOf(id)
        runCatching {
            val center = UNUserNotificationCenter.currentNotificationCenter()
            val body = UNMutableNotificationContent().apply {
                setTitle(title)
                setBody(content)
            }
            // trigger=null 立即触发; 先移除同 id 已展示通知, 模拟"更新同一条"
            val request = UNNotificationRequest.requestWithIdentifier(
                identifier = identifier,
                content = body,
                trigger = null,
            )
            center.removeDeliveredNotificationsWithIdentifiers(listOf(identifier))
            center.addNotificationRequest(request) { error ->
                if (error != null) {
                    AppLog.put("IosNotificationService.notify 失败: ${error.localizedDescription}")
                }
            }
        }.onFailure { AppLog.put("IosNotificationService.notify 失败: ${it.message}") }
    }

    override fun cancelNotification(id: Int) {
        val identifier = identifierOf(id)
        runCatching {
            val center = UNUserNotificationCenter.currentNotificationCenter()
            center.removeDeliveredNotificationsWithIdentifiers(listOf(identifier))
            center.removePendingNotificationRequestsWithIdentifiers(listOf(identifier))
        }
    }

    private fun identifierOf(id: Int): String = "legado-$id"
}

private object IosExternalRequestService : ExternalRequestService {
    // Swift 侧可传 NSURL (onOpenURL) 或字符串 (通知点击的 route:xxx)
    override fun parseLaunchRequest(request: Any): LaunchRequest? = when (request) {
        is String -> IosLaunchRequests.parse(request)
        is NSURL -> request.absoluteString?.let(IosLaunchRequests::parse)
        else -> null
    }

    // 实际处理由 LegadoApp 经 LaunchRequestBus 消费 (对照 Android/鸿蒙端同为 false)
    override fun handleLaunchRequest(request: LaunchRequest): Boolean = false
}

/**
 * iOS 外部启动请求解析/投递 (对照 app 端 `Intent.toLaunchRequest` / 鸿蒙 `OhosLaunchRequests`)。
 *
 * URL Scheme (`legado://`) 与"文件"应用打开 (`file://`) 均经 SwiftUI `onOpenURL` 进入;
 * 通知点击等携带路由的场景约定前缀 `route:`。
 */
object IosLaunchRequests {

    /** URI → [LaunchRequest]; 无法识别返回 null。 */
    fun parse(uri: String): LaunchRequest? {
        val value = uri.trim()
        if (value.isEmpty()) return null
        if (value.startsWith(ROUTE_PREFIX)) {
            return value.removePrefix(ROUTE_PREFIX).takeIf { it.isNotEmpty() }
                ?.let(LaunchRequest::NavigateTo)
        }
        return when (value.substringBefore("://", missingDelimiterValue = "").lowercase()) {
            // "文件" app / 其他应用共享进来的本地文件与 Android content/file 同语义
            "file", "content", "app" -> LaunchRequest.ImportFile(value)
            "" -> null
            else -> LaunchRequest.DeepLink(value)
        }
    }

    /** 投递到 [LaunchRequestBus]; 队列已关闭返回 false。 */
    fun post(request: LaunchRequest): Boolean =
        runCatching { LaunchRequestBus.dispatch(request) }.isSuccess

    private const val ROUTE_PREFIX = "route:"
}

/** Swift 侧入口: 解析并投递外部启动请求 (对照 [io.legado.app.handleLegadoDeepLink])。 */
fun handleIosLaunchRequest(uri: String): Boolean =
    IosLaunchRequests.parse(uri)?.let(IosLaunchRequests::post) ?: false
