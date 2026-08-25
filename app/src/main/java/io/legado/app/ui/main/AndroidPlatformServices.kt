package io.legado.app.ui.main

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.legado.app.App
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.resolveImagePath
import io.legado.app.model.bakeCoverImageFile
import io.legado.app.model.bakedImagePath
import io.legado.app.model.deleteImageIfUnreferenced
import io.legado.app.notificationManager
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
import io.legado.app.ui.root.importImageSetFile
import io.legado.app.utils.ActivityResultLauncherAwait
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.checkWrite
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.list
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.realScreenSize
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Android 端 PlatformServices 实现：聚合 10 项平台能力，
 * 经 PlatformServiceProviders.register 注册后供 shared LegadoApp 使用。
 *
 * 构造时持有 MainActivity 引用，用于 SAF launcher / 权限请求 / 窗口控制等需 Activity 上下文的能力。
 * SAF launcher 由 MainActivity 在 STARTED 前注册后传入, files 经 runBlocking 阻塞等待回调。
 */
class AndroidPlatformServices(
    private val activity: MainActivity,
    private val capabilitiesImpl: AndroidPlatformCapabilities,
    private val openDocumentPicker: ActivityResultLauncherAwait<Array<String>, Uri?>,
    private val openDocumentsPicker: ActivityResultLauncherAwait<Array<String>, List<Uri>>,
    private val createDocumentPicker: ActivityResultLauncherAwait<String, Uri?>,
    private val openDocumentTreePicker: ActivityResultLauncherAwait<Uri?, Uri?>,
) : PlatformServices {

    override val capabilities: PlatformCapabilities
        get() = capabilitiesImpl

    override val files: FilePickerService = AndroidFilePickerService(
        activity, openDocumentPicker, openDocumentsPicker, createDocumentPicker, openDocumentTreePicker,
    )

    override val sharing: ShareService = AndroidShareService(activity)

    override val browser: BrowserService = AndroidBrowserService(activity)

    override val permissions: PermissionService = AndroidPermissionService(activity)

    override val window: WindowController = AndroidWindowController(activity)

    override val keyboard: KeyboardController = AndroidKeyboardController(activity)

    override val media: MediaService = AndroidMediaService()

    override val notifications: NotificationService = AndroidNotificationService()

    override val externalRequests: ExternalRequestService = AndroidExternalRequestService()

    override val crashLogs: CrashLogProvider = AndroidCrashLogProvider(activity)
}

// SAF 文件选择/保存桥接: launcher 由 MainActivity 在 STARTED 前注册传入,
// 调用方均在 IoDispatcher 协程内, runBlocking 阻塞 IO 线程等待主线程 Activity Result 回调
private class AndroidFilePickerService(
    // 选图裁剪 (processWelcomeImage) 需读窗口尺寸与 toast, 与其余能力共用 MainActivity 引用
    private val activity: MainActivity,
    private val openDocumentPicker: ActivityResultLauncherAwait<Array<String>, Uri?>,
    private val openDocumentsPicker: ActivityResultLauncherAwait<Array<String>, List<Uri>>,
    private val createDocumentPicker: ActivityResultLauncherAwait<String, Uri?>,
    private val openDocumentTreePicker: ActivityResultLauncherAwait<Uri?, Uri?>,
) : FilePickerService {

    override fun pickFile(filter: FileFilter): String? = runBlocking {
        withContext(Dispatchers.Main) { openDocumentPicker.launch(filter.toMimeTypes()) }
            ?.toString()
            ?.takeIf { filter.matchesUri(it) }
            ?.let(::materializeUri)
    }

    override fun pickFiles(filter: FileFilter): List<String> = runBlocking {
        withContext(Dispatchers.Main) { openDocumentsPicker.launch(filter.toMimeTypes()) }
            .map { it.toString() }
            .filter { filter.matchesUri(it) }
            .mapNotNull(::materializeUri)
    }

    override fun saveFile(suggestedName: String, defaultDir: String?): String? = runBlocking {
        // CreateDocument 由系统决定保存位置, defaultDir 不支持指定
        withContext(Dispatchers.Main) { createDocumentPicker.launch(suggestedName) }?.toString()
    }

    // 图集导入 + 按真实屏幕尺寸烘焙清晰产物 (启动图与主题背景图共用)。
    // 返回裸文件名相对引用; 导入失败 null (调用方回落原路径); 烘焙失败只记日志——
    // 原图已入图集, 渲染端冷路径 (ensureBaked*) 会现场重烘焙。阻塞 IO。
    private fun importAndBakeCoverImage(srcPath: String): String? {
        val ref = importImageSetFile(srcPath) ?: return null
        val absSrc = resolveImagePath(ref)!!
        val screen = activity.windowManager.realScreenSize()
        if (!bakeCoverImageFile(absSrc, bakedImagePath(absSrc), screen.x, screen.y)) {
            AppLog.put("图集图烘焙失败: $absSrc")
        }
        return ref
    }

    // 选图导入: 原图复制进图集目录 (备份链路) + 按真实屏幕尺寸居中裁剪+缩小烘焙产物写缓存
    // (使用链路, 烘焙核心在 shared WallpaperBaker), 启动时不再解码整张大图；
    // 原图导入失败 toast + 记日志, 返回 null 由调用方回落原路径。
    // 裁剪比例取真实屏幕尺寸 (含状态栏, 对照原版 setCoverFromUri 的 getRealMetrics;
    // 旧实现用 windowManager.windowSize 是扣系统栏的内容区, 竖屏构图少算状态栏一条, 已废)
    override fun processWelcomeImage(
        srcPath: String,
        oldPath: String?,
        isNight: Boolean,
    ): String? = runCatching {
        val ref = importAndBakeCoverImage(srcPath) ?: return@runCatching null
        // 旧图清理 (对照原版 setCoverFromUri 开头的删除逻辑):
        // 旧 pref 值可能是相对引用/旧绝对路径/旧机制的处理图, 统一经 resolveImagePath 解析后比较;
        // 四键引用保护 (同图可能被启动封面另一模式/界面背景日/夜复用), 无引用才删
        val resolvedOld = resolveImagePath(oldPath)
        if (resolvedOld != null && resolvedOld != resolveImagePath(ref)) {
            deleteImageIfUnreferenced(
                resolvedOld,
                withFile = true,
                excludeKey = if (isNight) PreferKey.welcomeImageDark else PreferKey.welcomeImage,
            )
        }
        ref
    }.onFailure {
        AppLog.put("欢迎图导入失败", it)
        activity.toastOnUi(it.localizedMessage ?: it.toString())
    }.getOrNull()

    /**
     * 主题背景图导入: 与启动图同链路 ([importAndBakeCoverImage], 真实屏幕尺寸烘焙);
     * 失败 toast + 记日志, 渲染端读不到产物时冷路径现场重烘焙。
     */
    override fun importBackgroundImage(srcPath: String, isNight: Boolean): String? = runCatching {
        importAndBakeCoverImage(srcPath)
    }.onFailure {
        AppLog.put("主题背景图导入失败", it)
        activity.toastOnUi(it.localizedMessage ?: it.toString())
    }.getOrNull()

    // SAF 物化副本清理: 只删 cacheDir/file_picker 下的自建临时文件, 用户原文件不碰
    override fun discardPickedFile(path: String) {
        val tempDir = File(App.instance.cacheDir, "file_picker")
        val file = File(path)
        if (file.parentFile?.absolutePath == tempDir.absolutePath) {
            runCatching { file.delete() }
        }
    }

    override fun saveImageBytes(suggestedName: String, bytes: ByteArray): Boolean? = runBlocking {
        // CreateDocument 选位置 → contentResolver 写入 (对照 master PhotoDialog 的 SAF 保存)
        // 用户取消选择位置返回 null, 调用方静默; 仅写入失败返回 false
        val uri = withContext(Dispatchers.Main) { createDocumentPicker.launch(suggestedName) }
            ?: return@runBlocking null
        runCatching {
            App.instance.contentResolver.openOutputStream(uri)
                ?.use { it.write(bytes) } ?: return@runBlocking false
            true
        }.getOrDefault(false)
    }

    /**
     * 备份目录可写性预检 (对照 app 端 BackupConfigFragment.backup:
     * FileDoc.fromDir(path).checkWrite())。content:// 走 SAF DocumentFile 判断,
     * 普通路径 (桌面等无 SAF 平台) 视为可写。
     */
    override fun checkWrite(path: String): Boolean = runBlocking {
        val uri = path.toUri()
        if (uri.scheme?.lowercase() != "content") {
            true
        } else {
            withContext(Dispatchers.IO) {
                runCatching { FileDoc.fromDir(uri).checkWrite() }.getOrDefault(false)
            }
        }
    }

    // 选目录: OpenDocumentTree (对照 app 端 HandleFileDialog.selectDocTree),
    // 选中后 takePersistableUriPermission 保证重启后仍可访问
    override val supportsDirWrite: Boolean = true

    // 写进已选目录 (对照 app 端 FileUtils.saveImage(dirUri): FileDoc 兼容 content:// 与 file://)
    override fun writeImageToDir(dir: String, fileName: String, bytes: ByteArray): Boolean =
        runCatching {
            val picFile = FileDoc.fromDir(dir.toUri()).createFileIfNotExist(fileName)
            picFile.openOutputStream().getOrThrow().use { it.write(bytes) }
            true
        }.getOrDefault(false)

    override fun pickDirectory(): String? = runBlocking {
        withContext(Dispatchers.Main) { openDocumentTreePicker.launch(null) }?.let { uri ->
            val modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                App.instance.contentResolver.takePersistableUriPermission(uri, modeFlags)
            }.onFailure {
                AppLog.put("AndroidFilePickerService.pickDirectory takePersistableUriPermission 失败: ${it.localizedMessage}")
            }
            uri.toString()
        }
    }

    /**
     * SAF 返回的 content:// URI 不能直接交给 java.io.File。
     * 原版 BgTextConfigViewModel.setBgFromUri 直接从 URI 读流；这里把选择结果
     * 先复制到 cache，再把普通本地路径交给 shared 的跨平台文件逻辑。
     * 这样阅读背景、封面和其它文件导入都不会因 URI scheme 丢失而失败。
     */
    private fun materializeUri(uriString: String): String? {
        val uri = uriString.toUri()
        return when (uri.scheme?.lowercase()) {
            "content" -> runCatching {
                val resolver = App.instance.contentResolver
                val displayName = resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        cursor.getString(nameIndex)
                    } else {
                        null
                    }
                }
                val sourceName = displayName
                    ?.substringAfterLast(':')
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment
                        ?.substringAfterLast(':')
                        ?.substringAfterLast('/')
                        ?.takeIf { it.isNotBlank() }
                    ?: "picked_${System.currentTimeMillis()}"
                val safeName = sourceName.map { c ->
                    if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_'
                }.joinToString("").ifBlank { "picked_${System.currentTimeMillis()}" }
                val targetDir = File(App.instance.cacheDir, "file_picker").apply { mkdirs() }
                val target = File(targetDir, "${System.currentTimeMillis()}_$safeName")
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: return null
                target.absolutePath
            }.onFailure {
                AppLog.put("AndroidFilePickerService 读取文件失败: ${it.localizedMessage}")
            }.getOrNull()

            "file" -> uri.path
            else -> uriString
        }
    }

    // OpenDocument 仅接受 MIME 过滤, 缺省回落 */*
    private fun FileFilter.toMimeTypes(): Array<String> =
        if (mimeTypes.isNotEmpty()) mimeTypes.toTypedArray() else arrayOf("*/*")

    private fun FileFilter.matchesUri(uriString: String): Boolean {
        // 已按 mime 过滤时不再用扩展名二次过滤: 原版选封面是 selectCover.launch { mode =
        // HandleFileContract.IMAGE }, 只给 mime 不带 allowExtensions —— 扩展名白名单是迁移期
        // 自加的限制, 会把 heic/avif 等新格式静默拒掉 (表现为"选本地图片作封面无效")
        if (mimeTypes.isNotEmpty()) return true
        if (extensions.isEmpty()) return true
        val path = uriString.toUri().lastPathSegment ?: return true
        val name = path.substringAfterLast(':').substringAfterLast('/')
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension.isEmpty() || extensions.any { it.trimStart('.').lowercase() == extension }
    }
}

private class AndroidShareService(
    private val activity: MainActivity,
) : ShareService {
    override fun shareText(text: String) {
        activity.share(text)
    }

    override fun shareFile(filePath: String, mimeType: String) {
        val uri = filePath.toUri()
        if (uri.scheme == "content" || uri.scheme == "file") {
            activity.share(uri, mimeType)
        } else {
            // 纯路径走 FileProvider 转换 (对照 Context.share(File) 扩展)
            kotlin.runCatching { activity.share(File(filePath), mimeType) }
                .onFailure { AppLog.put("AndroidShareService.shareFile 失败: ${it.localizedMessage}") }
        }
    }
}

private class AndroidBrowserService(
    private val activity: MainActivity,
) : BrowserService {
    override fun openUrl(url: String) {
        activity.openUrl(url)
    }

    // 简单实现: 直接走系统浏览器, 后续可接入内置 WebView 路由
    override fun openUrlInApp(url: String) {
        activity.openUrl(url)
    }
}

private class AndroidPermissionService(
    private val activity: MainActivity,
) : PermissionService {
    override fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) ==
            PackageManager.PERMISSION_GRANTED

    // 异步请求, 同步返回仅表示是否成功发起请求
    override fun requestPermission(permission: String): Boolean {
        return kotlin.runCatching {
            ActivityCompat.requestPermissions(activity, arrayOf(permission), 0)
            true
        }.getOrElse {
            AppLog.put("AndroidPermissionService.requestPermission 失败: ${it.localizedMessage}")
            false
        }
    }
}

private class AndroidWindowController(
    private val activity: MainActivity,
) : WindowController {
    // 路由切换时不再翻转 legacy 布局标志: 窗口在 BaseComposeActivity.setupSystemBar 启动时
    // 已统一铺满到系统栏之后 (LAYOUT_FULLSCREEN + 透明系统栏), 各页面经 Compose insets
    // (statusBarsPadding 等) 自行避让。若再按路由 fullscreen 开关 setFullscreen, 书架 →
    // 阅读页 push 转场期间会翻转 LAYOUT_FULLSCREEN, 仍在屏上的书架整页重排、内容突然上顶
    // 到状态栏之下 (状态栏占位消失的跳动)。系统栏显隐一律交给 setSystemBars (insets
    // controller show/hide), 布局模式全程稳定。
    override val appliesPolicyFullscreen: Boolean get() = false

    override fun setFullscreen(enabled: Boolean) {
        // 布局模式由启动时统一建立, 此处 no-op; 保留空实现对齐 WindowController 契约
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        // 阅读页活动时交由 keepLight 超时逻辑 (upScreenTimeOut) 管理, 见 MainActivity.applyWindowKeepScreenOn
        activity.applyWindowKeepScreenOn(enabled)
    }

    override fun setOrientation(policy: OrientationPolicy) {
        activity.requestedOrientation = when (policy) {
            OrientationPolicy.Unspecified -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationPolicy.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationPolicy.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationPolicy.Sensor -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            OrientationPolicy.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        }
    }

    override fun setSystemBars(policy: SystemBarsPolicy) {
        val controller =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        when (policy) {
            SystemBarsPolicy.Default, SystemBarsPolicy.Visible -> {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }

            SystemBarsPolicy.Hidden, SystemBarsPolicy.Immersive -> {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            // 阅读页 hideStatusBar/hideNavigationBar 独立配置 (对照原版 insetsController 分栏处理)
            SystemBarsPolicy.HiddenStatusBar -> {
                controller.hide(WindowInsetsCompat.Type.statusBars())
                controller.show(WindowInsetsCompat.Type.navigationBars())
            }

            SystemBarsPolicy.HiddenNavigationBar -> {
                controller.show(WindowInsetsCompat.Type.statusBars())
                controller.hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
}

private class AndroidKeyboardController(
    private val activity: MainActivity,
) : KeyboardController {
    private val imm
        get() = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    override fun hideSoftInput() {
        val view = activity.currentFocus ?: activity.window.decorView
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun showSoftInput() {
        val view = activity.currentFocus ?: activity.window.decorView
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * `Window.setSoftInputMode` 是**整字段覆盖**, 且 mode == SOFT_INPUT_STATE_UNSPECIFIED(0)
     * 时 AOSP 直接丢弃不写 —— 所以除 Default 外每个分支都要显式带 adjust 位, 否则窗口的
     * adjust 停在上一页的值或厂商归一化结果 (实测 HyperOS 把 unspecified 归一成 adjustPan,
     * 会让 ViewRootImpl 平移窗口, 见 ImeInsets.kt)。
     */
    override fun setSoftInputPolicy(policy: SoftInputPolicy) {
        val mode = when (policy) {
            SoftInputPolicy.Default -> WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
            SoftInputPolicy.Resize -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            SoftInputPolicy.Pan -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            SoftInputPolicy.Hidden -> WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        activity.window.setSoftInputMode(mode)
    }
}

private class AndroidMediaService : MediaService {
    private var player: MediaPlayer? = null

    override fun playMedia(url: String, headers: Map<String, String>) {
        stopMedia()
        val newPlayer = MediaPlayer()
        player = newPlayer
        runCatching {
            newPlayer.setOnPreparedListener { it.start() }
            newPlayer.setOnCompletionListener { completed ->
                completed.release()
                if (player === completed) player = null
            }
            newPlayer.setOnErrorListener { failed, what, extra ->
                AppLog.put("AndroidMediaService 播放失败: what=$what extra=$extra")
                failed.release()
                if (player === failed) player = null
                true
            }
            newPlayer.setDataSource(App.instance, url.toUri(), headers)
            newPlayer.prepareAsync()
        }.onFailure {
            AppLog.put("AndroidMediaService.playMedia 失败: ${it.localizedMessage}")
            newPlayer.release()
            if (player === newPlayer) player = null
        }
    }

    override fun pauseMedia() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
            .onFailure { AppLog.put("AndroidMediaService.pauseMedia 失败: ${it.localizedMessage}") }
    }

    override fun stopMedia() {
        player?.let { current ->
            runCatching { current.stop() }
            current.release()
        }
        player = null
    }
}

private class AndroidNotificationService : NotificationService {
    override fun notify(id: Int, title: String, content: String) {
        kotlin.runCatching {
            val notification = NotificationCompat.Builder(App.instance, CHANNEL_ID_DEFAULT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(id, notification)
        }.onFailure {
            AppLog.put("AndroidNotificationService.notify 失败: ${it.localizedMessage}")
        }
    }

    override fun cancelNotification(id: Int) {
        notificationManager.cancel(id)
    }

    companion object {
        private const val CHANNEL_ID_DEFAULT = "platform_default"
    }
}

private class AndroidExternalRequestService : ExternalRequestService {
    // 解析 Intent 为 LaunchRequest: 委托给 [Intent.toLaunchRequest] (与 MainActivity 入口共用同一解析逻辑)
    override fun parseLaunchRequest(request: Any): LaunchRequest? {
        val intent = request as? Intent ?: return null
        return intent.toLaunchRequest()
    }

    // 实际处理由 LegadoApp.handleLaunchRequest 经 LaunchRequestBus 消费, 此处仅返回 false
    override fun handleLaunchRequest(request: LaunchRequest): Boolean {
        return false
    }
}

/**
 * 解析外部 Intent 为 [LaunchRequest]: 覆盖 VIEW(deep link / 文件关联) 与 PROCESS_TEXT / SEND。
 *
 * scheme 区分:
 * - content/file/app → [LaunchRequest.ImportFile] (走导入书籍流程)
 * - legado/yuedu/其他 → [LaunchRequest.DeepLink] (legado 系由 [LegadoDeepLinkHandler] 进一步接管导入对话框)
 *
 * 内部 startActivityForBook 兜底入口: bookUrl extra → [LaunchRequest.OpenReader],
 * 由 MainActivity 透传到 shared ReaderRoute 分发 (替代原 ReadBookActivity 直启)。
 */

/** 搜索界面入口别名: manifest activity-alias 指向 MainActivity (对照 master SearchActivity.receiptIntent)。 */
internal const val SEARCH_ALIAS = "io.legado.app.ui.book.search.SearchActivity"

/** 发现show 入口别名: manifest activity-alias 指向 MainActivity (对照 master ExploreShowActivity)。 */
internal const val EXPLORE_SHOW_ALIAS = "io.legado.app.ui.book.explore.ExploreShowActivity"

fun Intent.toLaunchRequest(): LaunchRequest? {
    // 通知/外部入口携带 route extra: 转为 NavigateTo 请求 (bookUrl 一并携带供冷启动兜底)
    getStringExtra("route")?.let {
        return LaunchRequest.NavigateTo(
            routeName = it,
            bookUrl = getStringExtra("bookUrl"),
        )
    }
    // 搜索界面 alias 入口 (对照 master SearchActivity.receiptIntent: key/searchScope/submit extra;
    // manifest activity-alias .ui.book.search.SearchActivity 指向本 Activity; 仅 alias 组件名命中
    // 才进入 —— 带 key extra 的普通 VIEW Intent 不再隐式视为搜索, 避免误吞无关 Intent;
    // key 为空也直达搜索页聚焦输入框, 对齐 master receiptIntent 的 isNullOrBlank 分支)
    if (component?.className == SEARCH_ALIAS) {
        return LaunchRequest.SearchBook(
            key = getStringExtra("key"),
            searchScope = getStringExtra("searchScope"),
            submit = getBooleanExtra("submit", true),
        )
    }
    // 发现show 入口 (对照 master ExploreShowActivity: exploreUrl/exploreName/sourceUrl extra;
    // manifest activity-alias .ui.book.explore.ExploreShowActivity 指向本 Activity)
    val isExploreShowAlias = component?.className == EXPLORE_SHOW_ALIAS
    val exploreUrl = getStringExtra("exploreUrl")?.takeIf { it.isNotBlank() }
    if (isExploreShowAlias || exploreUrl != null) {
        getStringExtra("sourceUrl")?.takeIf { it.isNotBlank() }?.let { sourceUrl ->
            return LaunchRequest.ExploreShow(
                sourceUrl = sourceUrl,
                exploreName = getStringExtra("exploreName"),
                exploreUrl = exploreUrl,
            )
        }
    }
    // startActivityForBook 兜底: bookUrl extra → OpenReader (chapterIndex/chapterPos 可选)
    getStringExtra("bookUrl")?.takeIf { it.isNotEmpty() }?.let { url ->
        // 同步消费 IntentData.book, 避免残留数据污染后续无关 Intent 解析
        IntentData.book
        return LaunchRequest.OpenReader(
            bookUrl = url,
            chapterIndex = getIntExtra("chapterIndex", -1).takeIf { it >= 0 },
            chapterPos = getIntExtra("chapterPos", -1).takeIf { it >= 0 },
        )
    }
    when (action) {
        Intent.ACTION_VIEW -> dataString?.let { url ->
            return when (data?.scheme) {
                "content", "file", "app" -> LaunchRequest.ImportFile(url)
                else -> LaunchRequest.DeepLink(url)
            }
        }

        Intent.ACTION_PROCESS_TEXT ->
            getStringExtra(Intent.EXTRA_PROCESS_TEXT)?.let { return LaunchRequest.ProcessText(it) }

        Intent.ACTION_SEND -> {
            @Suppress("DEPRECATION")
            getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                return LaunchRequest.ImportFile(it.toString())
            }
            getStringExtra(Intent.EXTRA_TEXT)?.let { return LaunchRequest.ProcessText(it) }
        }
    }
    // 过渡期: 未带 bookUrl extra 的旧调用方仍走 IntentData.book → 转 bookUrl 走 OpenReader
    return IntentData.book?.let { book -> LaunchRequest.OpenReader(bookUrl = book.bookUrl) }
}

/**
 * 崩溃日志提供者 Android 实现 (对照 app 端 CrashLogsDialog.CrashViewModel)。
 *
 * - loadCrashLogs: 从 externalCacheDir/crash 和备份路径收集崩溃日志文件
 * - readCrashLog: 读取单个崩溃日志文件内容
 * - clearCrashLogs: 删除所有崩溃日志文件
 * - shareCrashLog: 通过 Intent 分享文件
 */
private class AndroidCrashLogProvider(
    private val activity: MainActivity,
) : CrashLogProvider {

    override suspend fun loadCrashLogs(): List<CrashLogProvider.CrashLogEntry> =
        withContext(Dispatchers.IO) {
            val list = arrayListOf<FileDoc>()
            // 外部缓存目录 crash 子目录 (对照 CrashViewModel.initData)
            activity.externalCacheDir?.getFile("crash")?.listFiles { it.isFile }?.forEach {
                list.add(FileDoc.fromFile(it))
            }
            // 备份路径下的 crash 目录 (对照 CrashViewModel.initData backupPath 分支)
            val backupPath = AppConfig.backupPath
            if (!backupPath.isNullOrEmpty()) {
                val uri = backupPath.toUri()
                FileDoc.fromUri(uri, true).find("crash")?.list { !it.isDir }?.let {
                    list.addAll(it)
                }
            }
            list.sortedByDescending { it.name }.distinctBy { it.name }
                .map { CrashLogProvider.CrashLogEntry(it.name) }
        }

    override suspend fun readCrashLog(name: String): String? = withContext(Dispatchers.IO) {
        val fileDoc = findCrashFile(name) ?: return@withContext null
        runCatching { String(fileDoc.readBytes()) }.getOrNull()
    }

    override suspend fun clearCrashLogs() = withContext(Dispatchers.IO) {
        // 删外部缓存目录 crash 子目录
        activity.externalCacheDir?.getFile("crash")?.let { dir ->
            FileUtils.delete(dir, false)
        }
        // 删备份路径下的 crash 目录
        val backupPath = AppConfig.backupPath
        if (!backupPath.isNullOrEmpty()) {
            val uri = backupPath.toUri()
            FileDoc.fromUri(uri, true).find("crash")?.delete()
        }
    }

    override fun shareCrashLog(name: String) {
        val fileDoc = runBlocking { findCrashFile(name) } ?: return
        fileDoc.asFile()?.let {
            activity.share(it, title = "share")
        } ?: activity.share(fileDoc.uri, title = "share")
    }

    /** 按 name 查找崩溃日志 FileDoc (先查缓存目录, 再查备份路径) */
    private suspend fun findCrashFile(name: String): FileDoc? = withContext(Dispatchers.IO) {
        // 查缓存目录
        activity.externalCacheDir?.getFile("crash")?.listFiles { it.isFile }
            ?.firstOrNull { it.name == name }
            ?.let { return@withContext FileDoc.fromFile(it) }
        // 查备份路径
        val backupPath = AppConfig.backupPath
        if (!backupPath.isNullOrEmpty()) {
            val uri = backupPath.toUri()
            FileDoc.fromUri(uri, true).find("crash")?.list { !it.isDir }
                ?.firstOrNull { it.name == name }
                ?.let { return@withContext it }
        }
        null
    }
}
