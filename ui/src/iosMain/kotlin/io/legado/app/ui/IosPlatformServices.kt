@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui

import io.legado.app.constant.AppLog
import io.legado.app.constant.NotificationId
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.file.pickDirectory as pickDirectoryDocument
import io.legado.app.help.log.NativeCrashLogs
import io.legado.app.help.notification.IosNotificationCategories
import io.legado.app.help.notification.IosNotificationIdentifiers
import io.legado.app.help.notification.cancelIosUpdateBookRefresh
import io.legado.app.help.openURL
import io.legado.app.help.service.IosCacheBookNotifications
import io.legado.app.help.topMostViewController
import io.legado.app.ui.root.BrowserService
import io.legado.app.ui.root.CrashLogProvider
import io.legado.app.ui.root.ExternalRequestService
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.FilePickerService
import io.legado.app.ui.root.KeyboardController
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.LaunchRequestBus
import io.legado.app.ui.root.navigateToRoute
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
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIInterfaceOrientationMaskPortraitUpsideDown
import platform.UIKit.popoverPresentationController
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationDefaultActionIdentifier
import platform.UserNotifications.UNNotificationDismissActionIdentifier
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionNone
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
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

    // 崩溃日志: 崩溃现场 (logs/crash/crash-*.log, 未捕获异常钩子 + 信号处理器落盘)
    // 与运行日志 (logs/appLog-*.txt, recordLog 开启时落盘), 由 NativeCrashLogs 汇总;
    // 落盘/读取实现与鸿蒙端共用 nativeMain
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

        // add-only: 本应用只写相册 (UIImageWriteToSavedPhotosAlbum), 从不读 ——
        // 故只声明 NSPhotoLibraryAddUsageDescription, 权限级别必须同为 add-only。
        // (用 ReadWrite 会要求 NSPhotoLibraryUsageDescription, 缺失即被系统终止进程)
        IosPermission.Photos -> PHPhotoLibrary
            .authorizationStatusForAccessLevel(PHAccessLevelAddOnly)
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
                // 同上: add-only 与已声明的 NSPhotoLibraryAddUsageDescription 对应
                IosPermission.Photos -> PHPhotoLibrary
                    .requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { }

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
// 方向经 UIWindowScene.requestGeometryUpdateWithPreferences 请求前台场景切换允许方向
// (iOS 16+, 与视频页全屏共用 IosSceneOrientation.kt 一份实现; 阅读页/漫画页方向锁见 setOrientation)

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
        // 阅读页/漫画页方向锁 (对照原版 BaseReadActivity.setOrientation 的 "0"~"4" 分支,
        // 逐项映射见 WindowPolicy.readerOrientationPolicy 与 AndroidPlatformServices.setOrientation):
        // Unspecified/Sensor → 本应用 Info.plist 声明的全集 (Unspecified 即路由离开阅读页时的恢复),
        // Portrait → 仅竖屏, Landscape → 仅两横向, ReversePortrait → 倒竖屏。
        // 请求通道与视频页全屏共用一份 (见 requestSceneOrientation)。
        val mask = when (policy) {
            OrientationPolicy.Unspecified -> appSupportedOrientationMask()
            OrientationPolicy.Portrait -> UIInterfaceOrientationMaskPortrait
            OrientationPolicy.Landscape -> landscapeMask()
            OrientationPolicy.Sensor -> appSupportedOrientationMask()
            OrientationPolicy.ReversePortrait -> UIInterfaceOrientationMaskPortraitUpsideDown
        }
        requestSceneOrientation(mask, "阅读页方向")
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
// id 转固定 identifier 复用同一条 (对照 Android NotificationManager.notify(id, ...))。
// 非 private: 通知动作可能经"应用被杀后由系统在后台拉起"的路径到达 (该路径 UI 未创建,
// PlatformServiceProviders 未注册), 取消动作直接取本对象。
internal object IosNotificationService : NotificationService {

    override fun notify(id: Int, title: String, content: String) {
        val identifier = identifierOf(id)
        IosNotificationPublisher.publish(
            identifier, title, content, route = routeOf(id), category = categoryOf(id),
        )
    }

    /**
     * 发表不带自定义动作的同 id 通知 (覆盖式更新)。
     *
     * 用于结束态 (对照原版 `upExportNotification` 结束态分支与 `upCacheBookFinishNotification`
     * —— 两条在该状态都没有动作按钮)。
     */
    internal fun notifyWithoutActions(id: Int, title: String, content: String) {
        IosNotificationPublisher.publish(identifierOf(id), title, content, route = routeOf(id))
    }

    override fun cancelNotification(id: Int) {
        val identifier = identifierOf(id)
        IosNotificationPublisher.cancel(identifier)
    }

    private fun identifierOf(id: Int): String = IosNotificationIdentifiers.of(id)

    /**
     * 通知 id → 点击后的目标路由 (逐字对齐 app 端各通知 contentIntent 里的 route extra)。
     *
     * id 与 route 字符串两端都必须与 Android 一致 (NotificationId 常量与
     * `OhosLaunchRequests` 的 routeName 词表); 无对应页面的 id 返回 null (只拉起应用)。
     */
    private fun routeOf(id: Int): String? = when (id) {
        NotificationId.ReadAloudService -> "last_read"
        NotificationId.AudioPlayService -> "audio_play"
        NotificationId.ExportBookService -> "bookshelf_manage"
        NotificationId.CheckSourceService -> "book_source_manage"
        else -> null
    }

    /** 通知 id → 通知分类 (只有挂自定义动作的通知才需要; 返回 null 即通知上无动作按钮)。 */
    private fun categoryOf(id: Int): String? = IosNotificationCategories.of(id)

    /**
     * 通知标识 → 前台呈现选项 (0 = UNNotificationPresentationOptionNone, 即前台不展示)。
     *
     * 对照 app 端: 导出/缓存/校验三条在原版都是 `startForeground` 的前台服务通知, 应用在前台时
     * 同样常驻通知栏 ("从通知取消"因此在前台可达); 更新目录通知在原版应用可见时被
     * `AndroidUpdateBookCallback` 主动 stopService 撤掉, 故前台不呈现。
     *
     * 只呈现这三个标识 ([UNNotificationPresentationOptionList] = 仅进通知中心, 不弹横幅/
     * 不响铃/不加角标): 其余通知保持不展示。
     */
    internal fun foregroundPresentationOptions(identifier: String?): ULong = when (identifier) {
        identifierOf(NotificationId.ExportBookService),
        identifierOf(NotificationId.CacheBookService),
        identifierOf(NotificationId.CheckSourceService) -> UNNotificationPresentationOptionList

        else -> UNNotificationPresentationOptionNone
    }
}

/**
 * iOS 本地通知发送/取消 (带跳转路由的可选参数)。
 *
 * 为什么另开一个 object: [NotificationService] 接口 (commonMain) 只有 id/title/content 三参,
 * 而"点通知跳指定页"必须携带路由。接口签名不在本次改动范围, 故把带路由的重载放在这里 ——
 * 媒体通知等需要携带路由的调用方直接调本对象。
 */
object IosNotificationPublisher {

    /**
     * 发送 (或覆盖) 一条本地通知。
     *
     * @param route 非空时写进 userInfo 的 [IosNotificationRouteKey], 用户点击后由
     *   [IosNotificationDelegate] 解出并投递成 `route:` 前缀串 (与外部启动请求同一条解析链)
     * @param category 非空时挂通知分类 (须先经 [installCategories] 注册, 否则动作按钮不出现);
     *   只有带自定义动作的通知才需要
     */
    fun publish(
        identifier: String,
        title: String,
        body: String,
        route: String? = null,
        category: String? = null,
    ) {
        runCatching {
            val center = UNUserNotificationCenter.currentNotificationCenter()
            val payload = UNMutableNotificationContent().apply {
                setTitle(title)
                setBody(body)
                if (!route.isNullOrEmpty()) {
                    setUserInfo(mapOf(IosNotificationRouteKey to route))
                }
                if (!category.isNullOrEmpty()) {
                    setCategoryIdentifier(category)
                }
            }
            // trigger=null 立即触发; 先移除同 id 已展示通知, 模拟"更新同一条"
            val request = UNNotificationRequest.requestWithIdentifier(
                identifier = identifier,
                content = payload,
                trigger = null,
            )
            center.removeDeliveredNotificationsWithIdentifiers(listOf(identifier))
            center.addNotificationRequest(request) { error ->
                if (error != null) {
                    AppLog.put("iOS 通知发送失败: ${error.localizedDescription}")
                }
            }
        }.onFailure { AppLog.put("iOS 通知发送失败: ${it.message}") }
    }

    fun cancel(identifier: String) {
        runCatching {
            val center = UNUserNotificationCenter.currentNotificationCenter()
            center.removeDeliveredNotificationsWithIdentifiers(listOf(identifier))
            center.removePendingNotificationRequestsWithIdentifiers(listOf(identifier))
        }.onFailure { AppLog.put("iOS 通知取消失败: ${it.message}") }
    }

    /** 挂上 delegate (须在宿主 didFinishLaunching 内尽早调用; ARC 持有, 故存全局引用)。 */
    fun installDelegate() {
        UNUserNotificationCenter.currentNotificationCenter().delegate = IosNotificationDelegate
    }

    /** 注册通知分类 (自定义动作按钮生效的前提; 须在任何带动作的通知发送之前调用)。 */
    fun installCategories() {
        runCatching {
            UNUserNotificationCenter.currentNotificationCenter()
                .setNotificationCategories(IosNotificationCategories.all())
        }.onFailure { AppLog.put("iOS 通知分类注册失败: ${it.message}") }
    }
}

/** 通知 userInfo 里承载路由的 key (与 [IosNotificationPublisher.publish] 对应)。 */
internal const val IosNotificationRouteKey = "legadoRoute"

/**
 * 通知响应处理: 通知上的自定义动作 (取消) 就地执行, 点通知本体则解出 userInfo 里的路由,
 * 交回既有的外部启动请求解析链。
 *
 * 不新写路由解析: 复用 `route:` 前缀约定 (见 [IosLaunchRequests.parse]), 与系统
 * deep link / 文件关联走同一条链, 消费方仍是唯一的 `LaunchRequestBus` 消费者。
 * 各"取消"动作只调已有的平台取消入口 / 共享层取消链路 (逐分支见下), 不在此另写取消逻辑。
 */
private object IosNotificationDelegate : NSObject(), UNUserNotificationCenterDelegateProtocol {

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val content = didReceiveNotificationResponse.notification.request.content
        when (didReceiveNotificationResponse.actionIdentifier) {
            IosNotificationCategories.ExportCancel -> IosPlatformCapabilities.cancelExport()

            // 对照 app 端 UpdateBookService 的 IntentAction.stop (postEvent + 撤通知)
            IosNotificationCategories.UpdateBookCancel -> cancelIosUpdateBookRefresh()

            // 对照 app 端 CacheBookService 的 IntentAction.stop (stopSelf → onDestroy 停全部下载)
            IosNotificationCategories.CacheBookCancel -> IosCacheBookNotifications.cancelDownloads()

            // 对照 app 端 CheckSourceService 的 IntentAction.stop (stopSelf → onDestroy)
            IosNotificationCategories.CheckSourceCancel ->
                IosPlatformCapabilities.cancelCheckSource()

            // 用户显式清掉通知: 只有挂了 CustomDismissAction 的分类会投递本动作
            // (本仓仅导出分类挂了, 对照该通知原版的 setDeleteIntent)
            UNNotificationDismissActionIdentifier ->
                if (content.categoryIdentifier == IosNotificationCategories.Export) {
                    IosPlatformCapabilities.cancelExport()
                }

            // 点通知本体: 解出 userInfo 里的路由
            UNNotificationDefaultActionIdentifier -> {
                val route = content.userInfo[IosNotificationRouteKey] as? String
                navigateToRoute(route)?.let(IosLaunchRequests::post)
            }
        }
        withCompletionHandler()
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (ULong) -> Unit,
    ) {
        // 前台呈现按通知标识判定 (见 [IosNotificationService.foregroundPresentationOptions]):
        // 原版是前台服务通知的三条在前台也进通知中心 (不弹横幅), 使用户在前台也能从通知取消;
        // 其余通知返回 0 (UNNotificationPresentationOptionNone) 即前台不展示 (维持原行为)。
        withCompletionHandler(
            IosNotificationService.foregroundPresentationOptions(
                willPresentNotification.request.identifier
            )
        )
    }
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
            return navigateToRoute(value.removePrefix(ROUTE_PREFIX))
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

/**
 * Swift 侧入口: 把通知中心 delegate 装到 Kotlin 的 [IosNotificationDelegate]。
 *
 * 暴露成**顶层函数**而非让 Swift 直接写 `IosNotificationPublisher.shared.installDelegate()`:
 * 本仓 Swift 只调顶层函数 (`...Kt.xxx`) 这一种导出形状, 对象单例的导出名无法在本机编译验证。
 *
 * 调用时机: 宿主的 `application(_:didFinishLaunchingWithOptions:)` 返回**之前** ——
 * `UNUserNotificationCenter.delegate` 是单一全局槽位, 只有这时机才能接住"进程被回收后
 * 用户点通知冷启动"那一次投递 (晚于返回则可能被系统丢弃)。
 * [MainViewController] 里那次调用是保底, 同槽位重复赋值无副作用。
 */
fun installIosNotificationDelegate() {
    IosNotificationPublisher.installDelegate()
}
