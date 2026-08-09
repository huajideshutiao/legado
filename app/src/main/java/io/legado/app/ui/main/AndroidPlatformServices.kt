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
import io.legado.app.constant.AppLog
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
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
import io.legado.app.utils.ActivityResultLauncherAwait
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.delete
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.list
import io.legado.app.utils.openUrl
import io.legado.app.utils.share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.systemservices.notificationManager
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
        openDocumentPicker, openDocumentsPicker, createDocumentPicker, openDocumentTreePicker,
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

    // 选目录: OpenDocumentTree (对照 app 端 HandleFileDialog.selectDocTree),
    // 选中后 takePersistableUriPermission 保证重启后仍可访问
    override fun pickDirectory(): String? = runBlocking {
        withContext(Dispatchers.Main) { openDocumentTreePicker.launch(null) }?.let { uri ->
            val modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                appCtx.contentResolver.takePersistableUriPermission(uri, modeFlags)
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
        val uri = Uri.parse(uriString)
        return when (uri.scheme?.lowercase()) {
            "content" -> runCatching {
                val resolver = appCtx.contentResolver
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
                val targetDir = File(appCtx.cacheDir, "file_picker").apply { mkdirs() }
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
        if (extensions.isEmpty()) return true
        val path = Uri.parse(uriString).lastPathSegment ?: return true
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
        activity.keepScreenOn(enabled)
    }

    override fun setOrientation(policy: OrientationPolicy) {
        activity.requestedOrientation = when (policy) {
            OrientationPolicy.Unspecified -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationPolicy.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationPolicy.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationPolicy.Sensor -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
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

    override fun setSoftInputPolicy(policy: SoftInputPolicy) {
        val mode = when (policy) {
            SoftInputPolicy.Default -> WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
            SoftInputPolicy.Resize -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            SoftInputPolicy.Pan -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            SoftInputPolicy.Hidden -> WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
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
            newPlayer.setDataSource(appCtx, Uri.parse(url), headers)
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
            val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID_DEFAULT)
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
fun Intent.toLaunchRequest(): LaunchRequest? {
    // 通知/外部入口携带 route extra: 转为 NavigateTo 请求 (bookUrl 一并携带供冷启动兜底)
    getStringExtra("route")?.let {
        return LaunchRequest.NavigateTo(
            routeName = it,
            bookUrl = getStringExtra("bookUrl"),
        )
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
