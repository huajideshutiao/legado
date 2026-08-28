package io.legado.app.ui.main

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.legado.app.BuildConfig
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.PreferKey
import io.legado.app.constant.appInfo
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDav
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadConfigProviders
import io.legado.app.help.config.ReadTipConfigShared
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.image.ReaderImageCache
import io.legado.app.help.image.registerReaderImageResolver
import io.legado.app.help.storage.Backup
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.AndroidReadBookProvider
import io.legado.app.model.LocalReadBookProvider
import io.legado.app.model.ReadBook
import io.legado.app.model.fileBook.FileBook
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.ExportBookService
import io.legado.app.ui.association.DeepLinkImportHost
import io.legado.app.ui.association.LegadoDeepLink
import io.legado.app.ui.association.LegadoDeepLinkHandler
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.audio.SharedAudioPlayPlatformProvider
import io.legado.app.ui.book.changecover.AndroidCoverStorageService
import io.legado.app.ui.book.changecover.CoverStorageServiceProviders
import io.legado.app.ui.book.info.BookInfoBlurCoverBg
import io.legado.app.ui.book.info.BookInfoIntroImage
import io.legado.app.ui.book.info.LocalBlurCoverBgSlot
import io.legado.app.ui.book.info.LocalIntroImageSlot
import io.legado.app.ui.book.manga.AndroidMangaReaderPlatform
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.read.AndroidReaderPlatformProvider
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReaderPlatformProviders
import io.legado.app.ui.book.read.page.provider.AndroidTextMeasurer
import io.legado.app.ui.book.read.page.provider.TextMeasurerProviders
import io.legado.app.ui.book.source.SourceUiEventBridgeHost
import io.legado.app.ui.book.video.AndroidVideoPlayPlatformProvider
import io.legado.app.ui.book.video.VideoPlayPlatformProviders
import io.legado.app.ui.browser.AndroidWebView
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.dict.DictDialogHost
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.reader.ImageActionMenuEntry
import io.legado.app.ui.reader.ImageActionMenuRequest
import io.legado.app.ui.reader.ReaderImageActionMenu
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.LaunchRequestBus
import io.legado.app.ui.root.LegadoApp
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.observeEvent
import io.legado.app.utils.registerForActivityResult
import io.legado.app.utils.showExportSuccess
import io.legado.app.utils.startService
import io.legado.app.utils.sysScreenOffTime
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 主界面：零薄壳入口。Content 调用 shared [LegadoApp]，由 shared RouteContent 统一渲染。
 * 保留启动期逻辑（版本更新/本地密码/崩溃通知/备份同步）和平台专属回调（换封面/导入选目录）。
 */
class MainActivity : BaseComposeActivity(imageBg = false) {

    val viewModel by viewModels<MainViewModel>()

    private var exitTime: Long = 0
    private val EXIT_INTERVAL = 2000L
    private val exportBookPathKey = "exportBookPath"

    /** 外部入口直达请求暂存 (冷启动, 导航器未就绪): Content 组合后决定消费为初始路由
     *  或补发, 避免静默丢失 (book_info/read_book 壳转发 + search/explore-show 等 alias 入口)。 */
    private var pendingDirectRequest: LaunchRequest? = null

    /** 换封面源回调暂存: 由 [AndroidPlatformCapabilities.showChangeCoverDialog] 写入,
     *  ChangeCoverDialog 触发 [coverChangeTo] 时消费。 */
    var pendingCoverChangeCallback: ((String) -> Unit)? = null

    /** SAF 选书籍目录回调暂存: 由 [AndroidPlatformCapabilities.pickBookTreeUri] 写入,
     *  [bookTreeUriSelect] 回调时消费。 */
    var pendingBookTreeUriCallback: ((String?) -> Unit)? = null

    /** 查词请求 (选中词 → dictWord 暂存, 由 Content 渲染词典对话框; 对照原版 menu_dict → DictDialog)。 */
    private var dictWord by mutableStateOf<String?>(null)

    /** 图片保存目录选择 (对照原版 selectImageDir.launch: SAF 选目录 → 写 ACache imagePathKey)。 */
    private val selectImageDir = registerHandleFile { result ->
        val uri = result.uri ?: return@registerHandleFile
        ACache.get().put(AppConst.imagePathKey, uri.toString())
        // 有待保存图片 (menu_save 先选目录后保存) 则继续保存
        pendingSaveImageSrc?.let { src ->
            pendingSaveImageSrc = null
            saveImage(src, uri)
        }
    }

    /** 图片保存待处理 src (选择目录完成后继续执行保存)。 */
    private var pendingSaveImageSrc: String? = null

    // 阅读页屏幕常亮管理 (对照原版 ReadBookEventHandler.upScreenTimeOut/screenOffTimerStart)
    private val keepScreenOnHandler by lazy { Handler(Looper.getMainLooper()) }
    private val screenOffRunnable = Runnable {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private var readerWindowActive = false
    private var readerScreenTimeOut = 0L

    // 平台能力与服务: onActivityCreated 同步创建并注册, 修复 LaunchedEffect 异步注册时序问题
    private lateinit var capabilities: AndroidPlatformCapabilities

    /** 阅读页平台桥: Content 需挂它的自绘文本操作菜单宿主 (见 TextSelectionHost)。 */
    private lateinit var readerPlatform: AndroidReaderPlatformProvider
    private lateinit var services: AndroidPlatformServices

    /** 导入书籍: SAF 选根目录 (对照 ImportBookActivity.selectFolder: 写 pref 后 initRootDoc(true))。 */
    private val importSelectFolder = registerHandleFile { result ->
        if (result.uri != null) {
            AppConfig.importBookPath = result.uri.toString()
            // 取消选择时不动当前 rootDoc (对照原版 selectFolder 回调 uri==null 直接 return)
            pendingImportFolderCallback?.invoke()
            pendingImportFolderCallback = null
        }
    }

    /** 由 [AndroidPlatformCapabilities.pickImportFolder] 设置: 选完目录后重建 rootDoc 并加载。 */
    var pendingImportFolderCallback: (() -> Unit)? = null

    /** 暴露给 [AndroidPlatformCapabilities] 启动 SAF 选目录。 */
    fun launchImportFolderPicker() = importSelectFolder.launch()

    /** 其它设置: SAF 选书籍目录 (对照 OtherConfigHost.localBookTreeSelect, DIR_SYS)。 */
    private val bookTreeUriSelect = registerHandleFile { result ->
        pendingBookTreeUriCallback?.invoke(result.uri?.toString())
        pendingBookTreeUriCallback = null
    }

    /** 暴露给 [AndroidPlatformCapabilities] 启动 SAF 选书籍目录。 */
    fun launchBookTreeUriPicker() = bookTreeUriSelect.launch {
        title = androidAppString("select_book_folder")
        mode = HandleFileContract.DIR_SYS
    }

    /** 书架管理导出: 待导出书籍暂存 (由 [AndroidPlatformCapabilities.selectExportFolder] 写入,
     *  [exportDir] 回调 value=="cache" 时消费, 启动 ExportBookService)。 */
    var pendingExportBooks: List<Book>? = null

    /** 书架管理导出: HandleFileContract (对照 BookshelfManageActivity.exportDir)。 */
    private val exportDir by lazy {
        registerHandleFile { result ->
            val uri = result.uri ?: return@registerHandleFile
            if (result.value == "cache") {
                // 文件夹选择: 保存路径 + 启动 ExportBookService
                val dirPath = if (uri.isContentScheme()) uri.toString() else uri.path
                    ?: return@registerHandleFile
                ACache.get().put(exportBookPathKey, dirPath)
                val books = pendingExportBooks
                pendingExportBooks = null
                if (books != null && books.isNotEmpty()) {
                    // 对照 BookshelfManageActivity.exportDir 回调: 开启自定义导出时先弹章节配置对话框
                    if (AppConfig.enableCustomExport) {
                        PlatformCapabilityProviders.get()
                            .showExportSectionConfig(dirPath, books)
                    } else {
                        startExportBooks(dirPath, books)
                    }
                }
            } else {
                // 文件导出: 显示成功
                showExportSuccess(uri)
            }
        }
    }

    /** 暴露给 [AndroidPlatformCapabilities] 启动导出文件选择器 (EXPORT 模式, 对照 exportDir.launch EXPORT)。 */
    fun launchExportDir(name: String, file: java.io.File, type: String) {
        exportDir.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(name, file, type)
        }
    }

    /** 暴露给 [AndroidPlatformCapabilities] 启动导出文件夹选择器 (对照 selectExportFolder, value="cache")。 */
    fun launchExportFolderPicker(currentPath: String?) {
        val default = arrayListOf<SelectItem<Int>>()
        if (!currentPath.isNullOrEmpty()) {
            default.add(SelectItem(currentPath, -1))
        }
        exportDir.launch {
            otherActions = default
            value = "cache"
        }
    }

    /** 启动 ExportBookService 批量导出 (对照 BookshelfManageActivity.startExport)。 */
    internal fun startExportBooks(path: String, books: List<Book>) {
        if (books.isEmpty()) {
            toastOnUi(androidAppString("no_book"))
            return
        }
        val defaultType = when (AppConfig.exportType) {
            1 -> "epub"
            else -> "txt"
        }
        books.forEach { book ->
            val exportType = if (book.isImage) "cbz" else defaultType
            startService<ExportBookService> {
                action = IntentAction.start
                putExtra("bookUrl", book.bookUrl)
                putExtra("exportType", exportType)
                putExtra("exportPath", path)
            }
        }
    }

    /** 自定义导出: 按章节范围/分卷大小导出为 epub (对照 configExportSection 的自定义分支)。 */
    internal fun startExportBooksCustom(
        path: String,
        books: List<Book>,
        epubSize: Int,
        epubScope: String,
    ) {
        if (books.isEmpty()) {
            toastOnUi(androidAppString("no_book"))
            return
        }
        books.forEach { book ->
            startService<ExportBookService> {
                action = IntentAction.start
                putExtra("bookUrl", book.bookUrl)
                putExtra("exportType", "epub")
                putExtra("exportPath", path)
                putExtra("epubSize", epubSize)
                putExtra("epubScope", epubScope)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        initializePlatform()
        super.onCreate(savedInstanceState)
    }

    // FilePickerService 桥接: launcher 须在 Activity STARTED 前注册, 交给 AndroidFilePickerService 阻塞等待回调
    private val openDocumentPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument())
    private val openDocumentsPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments())
    private val createDocumentPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*"))

    // 选目录: SAF OpenDocumentTree (备份路径用), 与 selectDocTree 同语义
    private val openDocumentTreePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree())

    private fun coverChangeTo(coverUrl: String) {
        pendingCoverChangeCallback?.invoke(coverUrl)
        pendingCoverChangeCallback = null
    }

    /**
     * 查词 (对照原版 TextActionMenu 的 menu_dict → DictDialog): 阅读页文本菜单调用,
     * 由 Content 里的 [DictDialogHost] 渲染。
     */
    fun showDictWord(word: String) {
        dictWord = word
    }

    /**
     * 收起图片操作浮动菜单（对照文本菜单 dismiss; 幂等：菜单未显示时无操作）。
     */
    fun dismissImageActionMenu() {
        ReaderImageActionMenu.dismiss()
    }

    /**
     * 图片长按菜单 (对照原版 ReadBookActivity.onImageLongPress: 查看/刷新/保存/选择目录),
     * 自绘浮动菜单与文本长按菜单同款样式 (澎湃浮动菜单, 见 ReaderImageActionMenu)。
     */
    fun showImageActionMenu(src: String, x: Float, y: Float) {
        // 各动作收尾对照原版 popupAction 菜单项点击 → mode.finish → onDismiss →
        // postSelectionCancel (取消页内图片菜单标志, 防残留吞掉下一次点击)
        fun finish() {
            ReaderImageActionMenu.dismiss()
            ReadBookEvents.postSelectionCancel()
        }

        fun entry(label: String, action: () -> Unit) =
            ImageActionMenuEntry(label) {
                action()
                finish()
            }
        // 锚点矩形按长按点取 40px 方区 (对照旧 PopupAction contentRect 20px 留边),
        // 窗口坐标与自绘弹层宿主父节点坐标空间一致
        val anchor = Rect(x - 20f, y - 20f, x + 20f, y + 20f)
        ReaderImageActionMenu.show(
            ImageActionMenuRequest(
                anchor = anchor,
                entries = listOf(
                    entry(androidAppString("show")) { capabilities.showImagePreview(src, -1) },
                    entry(androidAppString("refresh")) { refreshImage(src) },
                    entry(androidAppString("action_save")) {
                        val path = ACache.get().getAsString(AppConst.imagePathKey)
                        if (path.isNullOrEmpty()) {
                            pendingSaveImageSrc = src
                            selectImageDir.launch {
                                mode = HandleFileContract.DIR_SYS
                            }
                        } else {
                            saveImage(src, path.toUri())
                        }
                    },
                    entry(androidAppString("select_folder")) {
                        selectImageDir.launch {
                            mode = HandleFileContract.DIR_SYS
                        }
                    },
                ),
                onDismiss = { finish() },
            )
        )
    }

    /**
     * 刷新图片 (对照原版 ReadBookViewModel.refreshImage): 删缓存文件 + 清内存缓存 + 重排。
     * 迁移版内存缓存为 shared ReaderImageCache (单书作用域, clear 清当前书);
     * 磁盘缓存文件沿用 BookHelp.getImage 路径 (原版同一路径)。
     */
    private fun refreshImage(src: String) {
        Coroutine.async(context = Dispatchers.IO) {
            ReadBook.book?.let { book ->
                val vFile = BookHelp.getImage(book, src)
                ReaderImageCache.clear()
                vFile.delete()
            }
        }.onFinally {
            ReadBook.loadContent(false)
        }
    }

    /**
     * 保存图片到用户目录 (对照原版 BaseReadViewModel.saveImage):
     * 缓存文件存在 → 直接复制; 本地书 → 取原图流写入; 网络书缓存缺失 → 不处理 (原版行为)。
     */
    private fun saveImage(src: String, uri: Uri) {
        Coroutine.async(context = Dispatchers.IO) {
            val book = ReadBook.book ?: return@async
            val image = BookHelp.getImage(book, src)
            if (image.exists()) {
                FileUtils.saveImage(image, uri)
            } else if (book.isLocal) {
                FileBook.getImage(book, src)?.use { input ->
                    FileUtils.saveImage(input, uri, ".${BookHelp.getImageSuffix(src)}")
                }
            }
        }.onError {
            AppLog.put("保存图片出错\n${it.localizedMessage}", it)
            toastOnUi("保存图片出错\n${it.localizedMessage}")
        }.onSuccess {
            toastOnUi("保存图片成功")
        }
    }

    fun enterReaderWindow() {
        readerWindowActive = true
        upScreenTimeOut()
        upReaderSystemBars(menuVisible = false)
    }

    fun exitReaderWindow() {
        readerWindowActive = false
        keepScreenOnHandler.removeCallbacks(screenOffRunnable)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        services.window.setSystemBars(io.legado.app.ui.root.SystemBarsPolicy.Default)
    }

    /**
     * 阅读页屏幕超时管理 (对照原版 upScreenTimeOut):
     * AppConfig.keepLight 取值秒数 (0=跟随系统, 正数=常亮秒数, -1=永不熄屏)。
     */
    fun upScreenTimeOut() {
        val keepLightPrefer = runCatching { (AppConfig.keepLight ?: "0").toInt() }.getOrDefault(0)
        readerScreenTimeOut = keepLightPrefer * 1000L
        screenOffTimerStart()
    }

    /**
     * 窗口策略下发常亮的最终落点：阅读页活动时常亮由 [upScreenTimeOut] / [screenOffTimerStart]
     * 全权管理（keepLight 计时），窗口策略值不再直接作用——避免 SYSTEM_UI 等策略重应用把
     * 超时逻辑已清除/待清除的 FLAG_KEEP_SCREEN_ON 重新加回且不再计时（对照原版单一管理）。
     */
    fun applyWindowKeepScreenOn(enabled: Boolean) {
        if (readerWindowActive) {
            upScreenTimeOut()
        } else {
            keepScreenOn(enabled)
        }
    }

    /**
     * 重置阅读页常亮计时 (对照原版 screenOffTimerStart):
     * keepLight<0 恒常亮; keepLight 大于系统息屏时间时常亮并定时移除, 否则交还系统息屏。
     */
    fun screenOffTimerStart() {
        keepScreenOnHandler.removeCallbacks(screenOffRunnable)
        if (readerScreenTimeOut < 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        val t = readerScreenTimeOut - sysScreenOffTime
        if (t > 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepScreenOnHandler.postDelayed(screenOffRunnable, readerScreenTimeOut)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // 阅读页内触摸重置常亮计时 (对照原版 ReadView.onTouchEvent → callBack.screenOffTimerStart)
        if (readerWindowActive) {
            screenOffTimerStart()
        }
    }

    /**
     * 阅读页系统栏刷新：跟随 hideStatusBar/hideNavigationBar 配置与菜单显隐
     * (对照原版 upSystemUiVisibility 的 toolBarHide 语义)。
     */
    fun upReaderSystemBars(menuVisible: Boolean) {
        services.window.setSystemBars(
            io.legado.app.ui.root.readerSystemBarsPolicy(menuVisible)
        )
    }

    @Composable
    override fun Content() {
        // rememberSaveable + AppNavigatorSaver: recreate()/进程重建后恢复导航栈
        // (对照原版 FragmentManager 经 savedInstanceState 恢复 Fragment 栈,
        // RECREATE 事件重建 Activity 后用户仍停在原页, 不会弹回主界面)
        // 冷启动直达 (透明壳 book_info/read_book 转发): 初始路由直接是详情/阅读页,
        // 书架不进导航栈 (对照 master BookInfoActivity 直开); 恢复栈时 initialRoute 被忽略。
        // directRoute 只算一次 (remember), 作为"直达是否消费成功"的唯一事实
        val directRoute = remember { directRouteFromIntent(intent) }
        val navigator = rememberSaveable(saver = AppNavigatorSaver) {
            AppNavigator(initialRoute = directRoute ?: AppRoute.Main())
        }
        // 直达请求收尾: 初始路由真消费成功 (栈顶 == 目标路由) → 不补发;
        // 消费失败 (IntentData.book 缺失 / 恢复栈覆盖) → 补发到 LaunchRequestBus, 不静默丢失
        LaunchedEffect(Unit) {
            pendingDirectRequest?.let { req ->
                val consumed = directRoute != null &&
                    navigator.backStack.value.singleOrNull()?.route == directRoute
                if (!consumed) LaunchRequestBus.dispatch(req)
                pendingDirectRequest = null
            }
        }
        val screenModelStore = remember { ScreenModelStore() }
        val context = LocalContext.current

        // 阅读器注入: ReaderRoute/ReaderDrawStyle 消费, 缺省值是 error() 会崩;
        // readBookConfig 必须与全局 ReadBookConfigProviders 同实例, 否则配置写读分家
        val readConfigProviders = remember {
            object : ReadConfigProviders {
                override val readBookConfig = ReadBookConfigProviders.get()
                override val readTipConfig = ReadTipConfigShared(readBookConfig)
            }
        }
        val readBookProvider = remember { AndroidReadBookProvider() }

        Box(Modifier.fillMaxSize()) {
            // 封面渲染不再注入 app 端 View 实现, 各端统一走 shared 默认 SharedBookCover
            CompositionLocalProvider(
                LocalReadConfigProviders provides readConfigProviders,
                LocalReadBookProvider provides readBookProvider,
                // 注入 app 端 AndroidWebView 到 shared 路由 (Login/ReadRss/WebView), 覆盖 LocalWebViewSlot 兜底
                LocalWebViewSlot provides { config, modifier, callbacks ->
                    AndroidWebView(config, modifier, callbacks)
                },
                // 注入 app 端 BookInfoBlurCoverBg 到 shared 路由 (详情页模糊背景), 覆盖 LocalBlurCoverBgSlot 兜底
                LocalBlurCoverBgSlot provides { book, coverTick, inBookshelf, isEInkMode, modifier, land ->
                    BookInfoBlurCoverBg(book, coverTick, inBookshelf, isEInkMode, modifier, land)
                },
                // 注入 app 端 BookInfoIntroImage 到 shared 路由 (详情页简介图), 覆盖 LocalIntroImageSlot 兜底
                LocalIntroImageSlot provides { src, onClick ->
                    BookInfoIntroImage(src, onClick)
                },
            ) {
                LegadoApp(
                    navigator = navigator,
                    screenModelStore = screenModelStore,
                    capabilities = capabilities,
                    platformServices = services,
                )
            }
            // 书源 JS 弹窗事件桥宿主 (对照 desktop Main.kt / iOS MainViewController):
            // 订阅 FlowBus(SOURCE_UI_REQUEST) 弹共享登录/源变量/验证码对话框
            SourceUiEventBridgeHost()
            // legado:// deep link 导入对话框宿主 (对照 iOS/鸿蒙 MainViewController 末尾挂载)。
            // 仅前台 (RESUMED) 挂载: 透明壳 AssociationActivity 在前台时挂它自己的一份,
            // MainActivity 若同时消费共享 pending 会双弹窗 (后台 Activity 的 Compose Dialog
            // 窗口仍会显示); 壳 finish 后 MainActivity 回到前台, 宿主自然恢复。
            val lifecycleOwner = LocalLifecycleOwner.current
            var hostVisible by remember {
                mutableStateOf(
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                )
            }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    hostVisible = when (event) {
                        Lifecycle.Event.ON_RESUME -> true
                        Lifecycle.Event.ON_PAUSE -> false
                        else -> hostVisible
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            if (hostVisible) {
                DeepLinkImportHost()
            }
            // 阅读页长按文本的自绘浮动操作菜单 (对照桌面 DesktopReaderPlatformProvider.TextSelectionHost)
            readerPlatform.TextSelectionHost()
            // 阅读页长按图片的自绘浮动操作菜单 (与文本菜单同款样式, 见 ReaderImageActionMenu)
            ReaderImageActionMenu.Host()
            // 查词对话框 (选中词 → 词典查询, 本地/在线词典规则; 对照原版 menu_dict → DictDialog)
            dictWord?.let { word ->
                DictDialogHost(
                    word = word,
                    onDismiss = { dictWord = null },
                )
            }
        }
    }

    private fun initializePlatform() {
        // Content 在 BaseComposeActivity.onCreate 内首次组合，平台依赖必须先注册
        capabilities = AndroidPlatformCapabilities(this)
        services = AndroidPlatformServices(
            this, capabilities,
            openDocumentPicker, openDocumentsPicker, createDocumentPicker, openDocumentTreePicker,
        )
        PlatformCapabilityProviders.register(capabilities)
        PlatformServiceProviders.register(services)
        // 封面选图持久化 (对齐原版 externalFiles/covers/<md5>.<ext>)
        CoverStorageServiceProviders.register(AndroidCoverStorageService())
        readerPlatform = AndroidReaderPlatformProvider(this)
        ReaderPlatformProviders.register(readerPlatform)
        AudioPlayPlatformProviders.register(SharedAudioPlayPlatformProvider)
        MangaReaderScreenModel.Providers.register(AndroidMangaReaderPlatform)
        VideoPlayPlatformProviders.register(AndroidVideoPlayPlatformProvider(this))
        // 排版度量走真实字形（对照 TextStyleProvider.getPaints 的 contentPaint）
        TextMeasurerProviders.register { textSizePx, letterSpacingPx, fontPath ->
            AndroidTextMeasurer(TextPaint().apply {
                isAntiAlias = true
                typeface = readerContentTypeface(fontPath)
                textSize = textSizePx
                letterSpacing = if (textSizePx > 0f) letterSpacingPx / textSizePx else 0f
            })
        }
        // 共享阅读器的内嵌图片 (排版取尺寸 + Canvas 取位图); app 端自绘阅读页仍走 ImageProvider
        registerReaderImageResolver()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {

        // 返回键: navigator.pop 优先 (导航栈有内容时返回上一页), 失败后走双击退出
        onBackPressedDispatcher.addCallback(this) {
            val navigator = AppNavigatorProviders.getOrNull()
            if (navigator?.pop() == true) {
                return@addCallback
            }
            if (System.currentTimeMillis() - exitTime > EXIT_INTERVAL) {
                toastOnUi(androidAppString("double_click_exit"))
                exitTime = System.currentTimeMillis()
            } else {
                if (BaseReadAloudService.pause) {
                    finish()
                } else {
                    moveTaskToBack(true)
                }
            }
        }
        // 冷启动经 DeepLink/文件关联 intent-filter 命中: 解析启动 Intent
        handleExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // warm launch: singleTask 复用已运行实例, 新 VIEW intent 经 onNewIntent 派发
        handleExternalIntent(intent)
    }

    /**
     * 解析外部 Intent (DeepLink / 文件关联 / PROCESS_TEXT / 直达入口) 并投递到 shared:
     * - legado:// / yuedu:// → [LegadoDeepLinkHandler] → DeepLinkImportHost 弹导入对话框
     * - file:// / content:// / app:// → [LaunchRequest.ImportFile] → 导入书籍
     * - PROCESS_TEXT / SEND → [LaunchRequest.ProcessText] → 搜索
     * - book_info/read_book 壳转发 + search/explore-show alias → 直达初始路由
     *   (对照 master 各独立 Activity 冷启动直开, 书架不进导航栈)
     */
    private fun handleExternalIntent(intent: Intent?) {
        if (intent?.getStringExtra("action") == "readAloud") {
            MediaButtonReceiver.readAloud(this, false)
            return
        }
        val request = intent?.toLaunchRequest() ?: return
        // 直达入口 (book_info/read_book/search/explore-show/文件/PROCESS_TEXT):
        // 导航器已就绪 (热启动/恢复栈) 正常分发 push 到现有栈顶 (push 去重防双份);
        // 未就绪 (冷启动, Content 尚未组合) 暂存, 由 Content 决定消费为初始路由
        // 或补发 (判定统一收敛到"初始路由是否真消费成功", 不再依赖冷启动猜测, 不静默丢失)。
        // DeepLink 例外: 不走暂存, 保持直接进导入宿主 (legado 系) / LaunchRequestBus (非 legado 系)
        if (request !is LaunchRequest.DeepLink && AppNavigatorProviders.getOrNull() == null) {
            pendingDirectRequest = request
            return
        }
        when (request) {
            is LaunchRequest.DeepLink -> {
                // legado 系: 走 shared 导入宿主; 缺 src 等非法格式静默丢弃 (对齐 app 端 finish)
                if (LegadoDeepLink.isDeepLink(request.url)) {
                    LegadoDeepLinkHandler.handle(request.url)
                } else {
                    // 非 legado 系: 回落 LaunchRequestBus (经 handleLaunchRequest → WebView 兜底)
                    LaunchRequestBus.dispatch(request)
                }
            }

            else -> LaunchRequestBus.dispatch(request)
        }
    }

    /**
     * 冷启动直达路由: 初始路由直接是目标页, 书架不进导航栈
     * (对照 master 每屏独立 Activity 冷启动直开本页, back 即退)。
     * 覆盖:
     * - 透明壳转发 book_info/read_book (IntentData.book 直传, 不落库)
     * - SearchActivity alias (key/searchScope/submit extra)
     * - ExploreShowActivity alias (exploreName/exploreUrl/sourceUrl extra, 查源下沉路由内)
     * - 文件关联 / PROCESS_TEXT / SEND
     * 恢复栈时被 rememberSaveable 忽略; 直达参数缺失 (进程被杀) 回落默认书架。
     */
    private fun directRouteFromIntent(intent: Intent?): AppRoute? {
        // 透明壳 (AssociationActivity) book_info/read_book 转发: route extra 定位
        intent?.getStringExtra("route")?.let { routeName ->
            val book = IntentData.book as? Book ?: return null
            return when (routeName) {
                "book_info" -> AppRoute.BookInfo(book.toRouteRef())
                "read_book" -> book.toReadRoute()
                else -> null
            }
        }
        // 搜索 alias: 对照 master SearchActivity.receiptIntent (key 空聚焦输入框)
        if (intent?.component?.className == SEARCH_ALIAS) {
            return AppRoute.Search(
                key = intent.getStringExtra("key"),
                searchScope = intent.getStringExtra("searchScope"),
                submit = intent.getBooleanExtra("submit", true),
            )
        }
        // 发现show alias: 对照 master ExploreShowActivity 冷启动直开; 查源下沉到路由内
        if (intent?.component?.className == EXPLORE_SHOW_ALIAS) {
            val sourceUrl = intent.getStringExtra("sourceUrl")
                ?.takeIf { it.isNotBlank() } ?: return null
            return AppRoute.ExploreShowByUrl(
                sourceUrl = sourceUrl,
                title = intent.getStringExtra("exploreName"),
                exploreUrl = intent.getStringExtra("exploreUrl"),
            )
        }
        // 文件关联 / PROCESS_TEXT / SEND (对齐 toLaunchRequest 分支)
        return when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { url ->
                when (intent.data?.scheme) {
                    "content", "file", "app" -> AppRoute.ImportBook(url)
                    else -> null
                }
            }

            Intent.ACTION_PROCESS_TEXT ->
                intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)?.let {
                    AppRoute.Search(key = it, submit = true)
                }

            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                    AppRoute.ImportBook(it.toString())
                } ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                    AppRoute.Search(key = it, submit = true)
                }
            }

            else -> null
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.isActivityVisible = true
        viewModel.updateUpdateNotification()
    }

    override fun onStop() {
        super.onStop()
        viewModel.isActivityVisible = false
        if (isFinishing) {
            // 退出应用时取消刷新任务, 避免弹出通知
            viewModel.cancelRefreshJobs()
        } else {
            viewModel.updateUpdateNotification()
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        lifecycleScope.launch {
            //隐私协议
            if (!privacyPolicy()) return@launch
            //版本更新
            upVersion()
            //设置本地密码
            setLocalPassword()
            notifyAppCrash()
            //备份同步
            backupSync()
            //版本更新
            if (AppConfig.autoCheckUpdate) {
                AppUpdate.check(this@MainActivity.lifecycleScope, this@MainActivity, true)
            }
        }
        viewModel.postLoad()
    }

    /**
     * 用户隐私与协议 (对照原版 MainActivity.privacyPolicy):
     * 未同意时弹隐私协议对话框, 同意则 [LocalConfig.privacyPolicyOk] 置 true, 拒绝则退出应用。
     * 已同意直接返回 true (仅首启生效)。
     */
    private suspend fun privacyPolicy(): Boolean = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.privacyPolicyOk) {
            block.resume(true)
            return@sc
        }
        // privacyPolicy.md 统一存于 shared/src/commonMain/resources (共享一份), 经 classpath 读取
        val privacyPolicy = javaClass.classLoader
            ?.getResourceAsStream("privacyPolicy.md")
            ?.bufferedReader()
            ?.use { it.readText() }
        alert(androidAppString("privacy_policy"), privacyPolicy) {
            positiveButton(androidAppString("agree")) {
                LocalConfig.privacyPolicyOk = true
                block.resume(true)
            }
            negativeButton(androidAppString("refuse")) {
                finish()
                block.resume(false)
            }
            // 取消/返回键视为未同意, 不 finish (finish 仅发生在明确点拒绝时)。
            // 按钮点击后对话框同样会 dismiss, 此时 continuation 已被按钮 resume 过,
            // 不加守卫会二次 resume 抛 IllegalStateException: Already resumed
            onDismiss {
                if (block.isActive) block.resume(false)
            }
        }
    }

    /**
     * 版本更新日志
     * 帮助文档对话框已下沉 shared (HelpDialog): 经 help Overlay 读 appHelp.md 渲染,
     * 等待 Overlay 关闭后继续 (对照原版 TextDialog setOnDismissListener 语义)
     */
    private suspend fun upVersion() {
        if (LocalConfig.versionCode == AppConst.appInfo.versionCode) return
        LocalConfig.versionCode = AppConst.appInfo.versionCode
        if (!LocalConfig.isFirstOpenApp) return
        // Compose root 未就绪时不弹 (理论不会发生, onPostCreate 时已挂载)
        val navigator = AppNavigatorProviders.getOrNull() ?: return
        navigator.showOverlay(AppOverlay.Dialog(key = "help", payload = "appHelp"))
        navigator.overlays.first { list ->
            list.none { it.key == "help" }
        }
    }

    /**
     * 设置本地密码
     */
    private suspend fun setLocalPassword() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.password != null) {
            block.resume(null)
            return@sc
        }
        alert(
            androidAppString("set_local_password"),
            androidAppString("set_local_password_summary")
        ) {
            val getText = editTextView(hint = "password")
            onDismiss {
                block.resume(null)
            }
            okButton {
                LocalConfig.password = getText()
            }
            cancelButton {
                LocalConfig.password = ""
            }
        }
    }

    private fun notifyAppCrash() {
        if (!LocalConfig.appCrash || BuildConfig.DEBUG) {
            return
        }
        LocalConfig.appCrash = false
        alert(androidAppString("draw"), "检测到阅读发生了崩溃，是否打开崩溃日志以便报告问题？") {
            yesButton {
                AppNavigatorProviders.getOrNull()?.showOverlay(AppOverlay.Dialog("crash_logs"))
            }
            noButton()
        }
    }

    /**
     * 备份同步
     */
    private fun backupSync() {
        if (!AppConfig.autoCheckNewBackup) {
            return
        }
        lifecycleScope.launch {
            val lastBackupFile =
                withContext(IO) { AppWebDav.lastBackUp().getOrNull() } ?: return@launch
            if (lastBackupFile.lastModify - LocalConfig.lastBackup > DateUtils.MINUTE_IN_MILLIS) {
                LocalConfig.lastBackup = lastBackupFile.lastModify
                alert(androidAppString("restore"), androidAppString("webdav_after_local_restore_confirm")) {
                    cancelButton()
                    okButton {
                        viewModel.restoreWebDav(lastBackupFile.displayName)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(PreferKey.threadCount) {
            viewModel.upPool()
        }
    }

}

/**
 * 正文度量字体：[fontPath] = `ReadBookConfig.textFont`，与绘制侧 `loadReaderFontFamily`
 * （同为 `Typeface.createFromFile`）读同一文件；空路径 / 加载失败一并回落 SANS_SERIF。
 */
private fun readerContentTypeface(fontPath: String): Typeface {
    val base = runCatching {
        if (fontPath.isNotEmpty()) {
            Typeface.createFromFile(fontPath)
        } else {
            Typeface.SANS_SERIF
        }
    }.getOrDefault(Typeface.SANS_SERIF)
    return when (ReadBookConfig.textBold) {
        1 -> Typeface.create(base, Typeface.BOLD)
        2 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, 300, false)
        } else {
            Typeface.create(base, Typeface.NORMAL)
        }

        else -> Typeface.create(base, Typeface.NORMAL)
    }
}
