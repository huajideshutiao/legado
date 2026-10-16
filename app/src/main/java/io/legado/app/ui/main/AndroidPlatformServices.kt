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
import io.legado.app.constant.AppPattern
import io.legado.app.constant.DirUsage
import io.legado.app.constant.LOG_DIR_NAME
import io.legado.app.constant.PreferKey
import io.legado.app.help.crash.CRASH_LOG_FILE_PREFIX
import io.legado.app.help.book.effectiveBookSaveDir
import io.legado.app.help.config.resolveImagePath
import io.legado.app.help.file.AndroidDirAuthorizations
import io.legado.app.model.bakeCoverImageFile
import io.legado.app.model.bakedImagePath
import io.legado.app.model.deleteImageIfUnreferenced
import io.legado.app.notificationManager
import io.legado.app.ui.book.video.ExternalVideoHandle
import io.legado.app.ui.root.AppRoute
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
import io.legado.app.ui.root.PageSystemBarAppearance
import io.legado.app.ui.root.ShareService
import io.legado.app.ui.root.SoftInputPolicy
import io.legado.app.ui.root.SystemBarsPolicy
import io.legado.app.ui.root.VideoPlayTarget
import io.legado.app.ui.root.WindowController
import io.legado.app.ui.root.importImageSetFile
import io.legado.app.ui.root.navigateToRoute
import io.legado.app.ui.root.SavedRef
import io.legado.app.ui.video.VideoDirect
import io.legado.app.utils.ActivityResultLauncherAwait
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.checkWrite
import io.legado.app.utils.copyToDocumentAtomically
import io.legado.app.utils.delete
import io.legado.app.utils.displayName
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.hasPlayableScheme
import io.legado.app.utils.list
import io.legado.app.utils.openUrl
import io.legado.app.utils.realScreenSize
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeDocumentBytesAtomically
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
    private val createDocumentPicker: ActivityResultLauncherAwait<String, Uri?>,
    private val openDocumentTreePicker: ActivityResultLauncherAwait<Uri?, Uri?>,
) : PlatformServices {

    override val capabilities: PlatformCapabilities
        get() = capabilitiesImpl

    override val files: FilePickerService = AndroidFilePickerService(
        activity, openDocumentPicker, createDocumentPicker, openDocumentTreePicker,
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
    private val createDocumentPicker: ActivityResultLauncherAwait<String, Uri?>,
    private val openDocumentTreePicker: ActivityResultLauncherAwait<Uri?, Uri?>,
) : FilePickerService {

    override fun pickFile(filter: FileFilter): String? = runBlocking {
        withContext(Dispatchers.Main) { openDocumentPicker.launch(filter.toMimeTypes()) }
            ?.toString()
            ?.takeIf { filter.matchesUri(it) }
            ?.let(::materializeUri)
    }

    /**
     * 弹 SAF 单文件另存为并写入文本。引用是 `content://` 文档地址 —— 只能经文档写入通道写,
     * 拿它当文件路径写会失败。
     */
    override fun saveText(suggestedName: String, text: String): SavedRef? =
        runBlocking {
            // CreateDocument 由系统决定保存位置, defaultDir 不支持指定
            val uri = withContext(Dispatchers.Main) { createDocumentPicker.launch(suggestedName) }
                ?: return@runBlocking null
            // 目标所在目录允许整份就位时先写临时文档再改名, 否则直写
            check(
                writeDocumentBytesAtomically(
                    App.instance, uri, suggestedName, text.encodeToByteArray()
                )
            ) { "写入文件失败: $suggestedName" }
            SavedRef(uri.toString())
        }

    /** 同上, 内容按流复制 (大体积内容不整份读入内存)。 */
    override fun saveFromFile(
        srcPath: String,
        suggestedName: String
    ): SavedRef? = runBlocking {
        val uri = withContext(Dispatchers.Main) { createDocumentPicker.launch(suggestedName) }
            ?: return@runBlocking null
        val written = File(srcPath).inputStream().use {
            copyToDocumentAtomically(App.instance, uri, suggestedName, it)
        }
        check(written) { "写入文件失败: $suggestedName" }
        SavedRef(uri.toString())
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
        // CreateDocument 选位置 → 写入 (对照 master PhotoDialog 的 SAF 保存): 目标所在目录允许整份就位
        // 时先写临时文档再改名, 否则直写 (见 writeDocumentBytesAtomically; 单文档授权通常只能直写)
        // 用户取消选择位置返回 null, 调用方静默; 仅写入失败返回 false
        val uri = withContext(Dispatchers.Main) { createDocumentPicker.launch(suggestedName) }
            ?: return@runBlocking null
        runCatching {
            writeDocumentBytesAtomically(App.instance, uri, suggestedName, bytes)
        }.getOrDefault(false)
    }

    /**
     * 目录可写性预检 (对照 app 端 BackupConfigFragment.backup:
     * FileDoc.fromDir(path).checkWrite())。content:// 走 SAF DocumentFile 判断,
     * 普通路径 (桌面等无 SAF 平台) 视为可写。
     *
     * SAF 权限按树 URI 授予, 可写性与目录当初为何而选无关。
     */
    override fun checkWrite(path: String, usage: DirUsage): Boolean = runBlocking {
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
    // 选中后 takePersistableUriPermission 保证重启后仍可访问;
    // SAF 权限按树 URI 授予、本身没有用途维度, 故选中后按 [usage] 登记用途, 供本地书文件访问校验
    override val supportsDirWrite: Boolean = true

    // 写进已选目录 (对照 app 端 FileUtils.saveImage(dirUri)): 目录允许整份就位时先写临时文档再改名,
    // 否则直写 (见 writeDocumentBytesAtomically)
    override fun writeImageToDir(dir: String, fileName: String, bytes: ByteArray): Boolean =
        runCatching {
            writeDocumentBytesAtomically(App.instance, dir.toUri(), fileName, bytes)
        }.getOrDefault(false)

    override fun pickDirectory(usage: DirUsage): String? = runBlocking {
        withContext(Dispatchers.Main) { openDocumentTreePicker.launch(null) }?.let { uri ->
            val modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                App.instance.contentResolver.takePersistableUriPermission(uri, modeFlags)
            }.onFailure {
                AppLog.put("AndroidFilePickerService.pickDirectory takePersistableUriPermission 失败: ${it.localizedMessage}")
            }
            AndroidDirAuthorizations.remember(uri.toString(), usage)
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

    // 进入深色覆盖层前的图标明暗; null = 当前无人持有。嵌套打开不重复存, 否则会抹掉真正的原值
    private var savedLightStatusBars: Boolean? = null
    private var savedLightNavigationBars: Boolean? = null

    /** 当前窗口是否处于页面声明的系统栏外观 (见 [setPageSystemBarAppearance])。 */
    private var pageAppearanceApplied = false

    /** 当前生效的页面声明 (配置变化后重新盖回时取它)。 */
    private var pageAppearance: PageSystemBarAppearance? = null

    override fun setLightIconOverlay(enabled: Boolean) {
        val controller =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (enabled) {
            if (savedLightStatusBars == null) {
                savedLightStatusBars = controller.isAppearanceLightStatusBars
                savedLightNavigationBars = controller.isAppearanceLightNavigationBars
            }
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            return
        }
        // 退出覆盖层: 页面声明仍生效时按它恢复 (期间的声明变化也要跟上), 否则回到进覆盖层前的值
        val page = pageAppearance
        val savedStatus = savedLightStatusBars
        val savedNavigation = savedLightNavigationBars
        savedLightStatusBars = null
        savedLightNavigationBars = null
        if (page != null) {
            applyPageSystemBarAppearance(page)
        } else {
            savedStatus?.let { controller.isAppearanceLightStatusBars = it }
            savedNavigation?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }

    override fun setPageSystemBarAppearance(appearance: PageSystemBarAppearance?) {
        pageAppearance = appearance
        if (appearance == null) {
            // 当前无人声明: 回到非页面声明的规则
            // (主题状态栏色 / 壁纸贴边像素亮度 + 主题导航栏色)。
            // 本就不在页面外观状态时不重算 —— 非声明页每次路由变化都会走这条,
            // 而重算含壁纸贴边像素采样
            if (!pageAppearanceApplied) return
            pageAppearanceApplied = false
            activity.setupSystemBar()
            return
        }
        applyPageSystemBarAppearance(appearance)
    }

    override fun reapplyPageSystemBarAppearance() {
        pageAppearance?.let { applyPageSystemBarAppearance(it) }
        // 覆盖层持有图标期间 apply 会跳过图标下发, 而 setupSystemBar 已把它重设成主题值:
        // 这里把覆盖层要的浅色补回去 (覆盖层也在被重建的窗口上)
        if (savedLightStatusBars != null) {
            val controller =
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    private fun applyPageSystemBarAppearance(appearance: PageSystemBarAppearance) {
        pageAppearanceApplied = true
        // 深色覆盖层 (全屏看图) 持有期间不下发: 页顶与系统栏区都被覆盖层盖住, 图标该跟覆盖层的
        // 底色走, 页面重发会把覆盖层刚改白的图标盖回去 (旋转/多窗口后尤为明显); 声明存在
        // pageAppearance 里, 覆盖层退出时按那时最新的声明恢复 (见 setLightIconOverlay)
        if (savedLightStatusBars != null) return
        activity.setLightStatusBar(appearance.lightStatusBarIcons)
        // 对照原版 ReadBookActivity.upNavigationBarColor: 声明了底色的页面按该色
        // (setNavigationBarColorAuto 一并按该色亮度定导航栏图标明暗), 未声明走主题规则
        appearance.navigationBarColor?.let { activity.setNavigationBarColorAuto(it) }
            ?: activity.upNavigationBarColor()
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

    /**
     * `Window.setSoftInputMode` 是**整字段覆盖**, 且 mode == SOFT_INPUT_STATE_UNSPECIFIED(0)
     * 时 AOSP 直接丢弃不写 —— 所以除 Default 外每个分支都要显式带 adjust 位, 否则窗口的
     * adjust 停在上一页的值或厂商归一化结果 (实测 HyperOS 把 unspecified 归一成 adjustPan,
     * 会让 ViewRootImpl 平移窗口, 见 ImeInsets.kt)。
     */
    @Suppress("DEPRECATION") // SOFT_INPUT_ADJUST_RESIZE 已弃用, 仍是显式写 adjust 位的唯一通道
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
 * - 视频直投 (content/file/http(s) 命中 MIME 或视频扩展名) → [LaunchRequest.OpenRoute] 播放页
 *   (**排在下面书籍分流之前**, 见 [videoDirectTarget]; mp4 不是书, 落到 ImportFile 会被导入流程判不支持)
 * - content/file/app → [LaunchRequest.ImportFile] (走导入书籍流程)
 * - legado/yuedu/其他 → [LaunchRequest.DeepLink] (legado 系由 [LegadoDeepLinkHandler] 进一步接管导入对话框)
 *
 * 只解析"手里只有字符串标识"的入口 (通知 route extra / alias / 文件 URI / 选中文本);
 * 带内存对象的书籍类直达由投递方直接投 [LaunchRequest.OpenRoute], 不经 Intent。
 */

/** 搜索界面入口别名: manifest activity-alias 指向 MainActivity (对照 master SearchActivity.receiptIntent)。 */
internal const val SEARCH_ALIAS = "io.legado.app.ui.book.search.SearchActivity"

/** 发现show 入口别名: manifest activity-alias 指向 MainActivity (对照 master ExploreShowActivity)。 */
internal const val EXPLORE_SHOW_ALIAS = "io.legado.app.ui.book.explore.ExploreShowActivity"

fun Intent.toLaunchRequest(): LaunchRequest? {
    // 通知/外部入口携带 route extra: 转为 NavigateTo 请求 (bookUrl 一并携带供冷启动兜底)
    navigateToRoute(getStringExtra("route"), getStringExtra("bookUrl"))?.let {
        return it
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
    when (action) {
        Intent.ACTION_VIEW -> {
            // 视频排在书籍分流之前: 外部给的 mp4/mkv/m3u8 已是可播地址, 不是书也不是书源网页
            videoDirectTarget()?.let {
                return LaunchRequest.OpenRoute(AppRoute.VideoPlay(it))
            }
            dataString?.let { url ->
                // scheme 按 RFC 3986 大小写不敏感 (iOS/鸿蒙两端都已 lowercase): 旧写法拿原始
                // scheme 比, 大写 scheme 的 URI 会落 DeepLink 而非文件导入
                return when (data?.scheme?.lowercase()) {
                    "content", "file", "app" -> LaunchRequest.ImportFile(url)
                    else -> LaunchRequest.DeepLink(url)
                }
            }
        }

        Intent.ACTION_PROCESS_TEXT ->
            getStringExtra(Intent.EXTRA_PROCESS_TEXT)?.let { return LaunchRequest.ProcessText(it) }

        Intent.ACTION_SEND -> {
            // 分享 mp4 给阅读: manifest 的 SEND */* 挂在 ProcessTextActivity alias 上, 以前会
            // 一路进"导入本地书"页再被判不支持 —— 这里截给播放页
            videoDirectTarget()?.let {
                return LaunchRequest.OpenRoute(AppRoute.VideoPlay(it))
            }
            @Suppress("DEPRECATION")
            getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                return LaunchRequest.ImportFile(it.toString())
            }
            getStringExtra(Intent.EXTRA_TEXT)?.let { return LaunchRequest.ProcessText(it) }
        }
    }
    // 无法识别: 返回 null。不回落 IntentData.book —— 它取一次即失效, 而每个 MainActivity
    // intent (含普通启动 intent) 都过一遍本函数, 回落等于让无关 intent 取走别处刚寄存的书。
    return null
}

/**
 * 外部投递的视频 → 播放页直投载荷 (判据唯一来源: shared [VideoDirect], 四端共用一份)。
 *
 * 覆盖两种投递形态:
 * - `ACTION_VIEW`: [data] 是 content/file/http(s) URI (文件管理器"打开方式"、浏览器点直链)
 * - `ACTION_SEND`: [Intent.EXTRA_STREAM] 是视频 URI (分享面板), 或 [Intent.EXTRA_TEXT]
 *   本身就是一条带视频后缀的 http(s) 直链
 *
 * # 为什么不能只信 `intent.type`
 * 不少文件管理器给 mp4 报的是 `application/octet-stream` 甚至主/次类型全星号的通配 (它们本来就命中透明壳上
 * 那条 octet-stream filter); 反过来 `content://media/.../video/123` 的末段是数字 id 而不是
 * `x.mp4`, 那种只有 MIME 能认。两条判据缺一不可, 所以这里把 `type` 与查到的 `DISPLAY_NAME`
 * 合成一个 `mimeIsVideo` 交给 [VideoDirect] (扩展名那条它自己按地址末段判)。
 *
 * @return null = 这不是可直接播放的视频, 调用方继续按书 / JSON / 文本原链路处理
 */
fun Intent.videoDirectTarget(): VideoPlayTarget.Direct? {
    val mimeIsVideo = VideoDirect.isVideoMime(type)
    return when (action) {
        Intent.ACTION_VIEW -> data?.let { videoTargetOfUri(it, mimeIsVideo) }

        Intent.ACTION_SEND -> {
            @Suppress("DEPRECATION")
            val stream = getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (stream != null) {
                videoTargetOfUri(stream, mimeIsVideo)
            } else {
                // 分享的是纯文本: 只有整条就是一个可播地址 (结尾 .m3u8/.mp4 等) 才截给播放页,
                // 否则保持原语义 (交 ProcessText 分流), 不得把书名/普通文案当链接去播
                getStringExtra(Intent.EXTRA_TEXT)?.trim()
                    ?.takeIf { it.hasPlayableScheme() }
                    ?.let { VideoDirect.targetFor(it, mimeIsVideo) }
            }
        }

        else -> null
    }
}

/** URI → 直投载荷: 只负责补显示名 (标题 + 扩展名判据), 其余判定全在 [VideoDirect]。 */
private fun videoTargetOfUri(uri: Uri, mimeIsVideo: Boolean): VideoPlayTarget.Direct? {
    val address = uri.toString()
    if (!address.hasPlayableScheme()) return null
    val displayName = uri.displayName(App.instance)
    val target = VideoDirect.targetFor(
        address = address,
        // DISPLAY_NAME 命中视频后缀 等同于 MIME 命中 (content URI 的末段常不是文件名)
        mimeIsVideo = mimeIsVideo || displayName?.matches(AppPattern.videoFileRegex) == true,
        title = displayName,
    ) ?: return null
    // content:// 地址在此刻打开并持有句柄
    if (address.startsWith("content://", ignoreCase = true)) {
        ExternalVideoHandle.hold(App.instance, address)
    }
    return target
}

/**
 * 崩溃日志提供者 Android 实现 (对照 app 端 CrashLogsDialog.CrashViewModel)。
 *
 * - loadCrashLogs: 从 externalCacheDir/crash 和书籍保存位置收集崩溃日志文件
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
            // 外部缓存目录的日志目录 (对照 CrashViewModel.initData)。
            // 只收 crash- 前缀: 该目录同时放着运行日志与"上次异常退出"标记文件, 都不是崩溃现场
            activity.externalCacheDir?.getFile(LOG_DIR_NAME)
                ?.listFiles { it.isFile && it.name.startsWith(CRASH_LOG_FILE_PREFIX) }
                ?.forEach {
                    list.add(FileDoc.fromFile(it))
                }
            // 书籍保存位置下的日志目录 (对照 CrashViewModel.initData backupPath 分支)
            val savePath = effectiveBookSaveDir()
            if (!savePath.isNullOrEmpty()) {
                val uri = savePath.toUri()
                FileDoc.fromUri(uri, true).find(LOG_DIR_NAME)?.list { !it.isDir }?.let {
                    list.addAll(it.filter { doc -> doc.name.startsWith(CRASH_LOG_FILE_PREFIX) })
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
        // 只删崩溃现场文件, 目录本身留着: 该目录还放着运行日志与"上次异常退出"标记文件, 整目录删掉会把
        // 标记一起清掉, 下次启动就不再提示 (而用户清空日志并不等于否认刚才崩过)
        activity.externalCacheDir?.getFile(LOG_DIR_NAME)?.listFiles { it.isFile }?.forEach { file ->
            if (file.name.startsWith(CRASH_LOG_FILE_PREFIX)) {
                FileUtils.delete(file, false)
            }
        }
        // 删书籍保存位置下的日志目录里的崩溃现场 (SAF 侧只写崩溃日志, 无运行日志/标记文件)
        val savePath = effectiveBookSaveDir()
        if (!savePath.isNullOrEmpty()) {
            val uri = savePath.toUri()
            FileDoc.fromUri(uri, true).find(LOG_DIR_NAME)?.list { !it.isDir }
                ?.filter { it.name.startsWith(CRASH_LOG_FILE_PREFIX) }
                ?.forEach { it.delete() }
        }
    }

    override fun shareCrashLog(name: String) {
        val fileDoc = runBlocking { findCrashFile(name) } ?: return
        fileDoc.asFile()?.let {
            activity.share(it, title = "share")
        } ?: activity.share(fileDoc.uri, title = "share")
    }

    /** 按 name 查找崩溃日志 FileDoc (先查缓存目录, 再查书籍保存位置) */
    private suspend fun findCrashFile(name: String): FileDoc? = withContext(Dispatchers.IO) {
        // 查缓存目录
        activity.externalCacheDir?.getFile(LOG_DIR_NAME)?.listFiles { it.isFile }
            ?.firstOrNull { it.name == name }
            ?.let { return@withContext FileDoc.fromFile(it) }
        // 查书籍保存位置
        val savePath = effectiveBookSaveDir()
        if (!savePath.isNullOrEmpty()) {
            val uri = savePath.toUri()
            FileDoc.fromUri(uri, true).find(LOG_DIR_NAME)?.list { !it.isDir }
                ?.firstOrNull { it.name == name }
                ?.let { return@withContext it }
        }
        null
    }
}
