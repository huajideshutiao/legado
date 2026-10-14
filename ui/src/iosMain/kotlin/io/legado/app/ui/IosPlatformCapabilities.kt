@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.DirUsage
import io.legado.app.constant.EventBus
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.effectiveBookSaveDir
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.tts.IosReadAloudHost
import io.legado.app.service.defaultTtsEngineConfig
import io.legado.app.help.book.toShelfJsonMap
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.file.exportFile
import io.legado.app.help.config.effectiveBookExportDir
import io.legado.app.help.config.rememberBookExportDir
import io.legado.app.help.file.pickDirectory as pickDirectoryDocument
import io.legado.app.help.openURL
import io.legado.app.help.openURLIfPossible
import io.legado.app.help.copyToClipboard as copyTextToClipboard
import io.legado.app.help.readFromClipboard
import io.legado.app.help.DirectLinkUploadNativePrimitives
import io.legado.app.help.toast.Toasters
import io.legado.app.help.topMostViewController
import io.legado.app.help.upLoadToDirectLink
import io.legado.app.help.storage.NativeSaveLog
import io.legado.app.model.CheckSourceRunner
import io.legado.app.model.Debug
import io.legado.app.model.ExportBookUtils
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.info.WebFileImportShared
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.book.source.manage.BookSourceViewModelShared
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.DefaultDialogTransitionSpec
import io.legado.app.ui.root.DialogTransitionSpec
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteTransitionSpec
import io.legado.app.ui.root.TransitionEasing
import io.legado.app.ui.root.ensureBookSaveDirSelected
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.File
import io.legado.app.utils.GSON
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.IosSecurityScopeLease
import io.legado.app.utils.IosSecurityScopedStorage
import io.legado.app.utils.postEvent
import io.legado.app.utils.toJson
import io.legado.app.utils.textCharsetCodec
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.setAlternateIconName
import platform.UIKit.UIScreen
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UITextField
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 端 [io.legado.app.ui.root.PlatformCapabilities]: 内核已下沉的能力复用 [SharedPlatformCapabilities] (对照 desktop / 鸿蒙),
 * 需系统面板的能力走 UIKit (导出 UIDocumentPicker / 文本输入 UIAlertController)。
 * 依赖命令式对话框宿主 (主题列表/分组管理/书籍变量) 的能力保持 unsupported
 * (本地书导入浏览由 [NativeImportBook] 支持, 见下方"导入本地书"段)。
 */
object IosPlatformCapabilities : NativePlatformCapabilities {
    /**
     * 系统报告取件指引 (深链不可用时的回落文案)。
     *
     * 写成字面量而不是新增资源键: 它是 iOS 专属文案, 与 native 侧其余平台提示
     * (如"未设置备份目录")同一口径。
     */
    private const val CRASH_REPORT_HINT: String =
        "原生崩溃的完整调用栈由系统记录，应用内拿不到。\n" +
            "请到「设置 → 隐私与安全性 → 分析与改进 → 分析数据」中，" +
            "找到以应用名开头、时间与崩溃时刻一致的文件。"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 导出队列与"正在导出"标记的读写锁 (与队列读写同锁判断, 避免"收工瞬间入队"的书没人导)。 */
    private val exportLock = SynchronizedObject()

    /**
     * 待导出队列, 键为书 url (对照原版 ExportBookService.waitExportBooks): 重复触发导出时新请求
     * 入队稍后照常导出; 同一本书已在队列里就换成最新导出方式。
     */
    private val waitExportBooks = LinkedHashMap<String, PendingExport>()

    /** 正在导出的书 url (对照原版 exportProgress: 同一本书正在导出时不再重复入队)。 */
    private var exportingBookUrl: String? = null

    /** 队列执行者是否在跑。 */
    private var exportWorkerRunning = false

    /** 队列执行者 (对照 app 端 ExportBookService 的服务存活, 可取消)。 */
    private var exportBooksJob: Job? = null

    /** 队列里的一本书: 导出方式 (对照原版 ExportBookService 的 bookUrl → ExportConfig)。 */
    private class PendingExport(val book: Book, val mode: ExportMode)

    /** 导出方式 (对照原版 ExportBookService.ExportConfig: type 或 epubScope+epubSize)。 */
    private sealed interface ExportMode {
        /** 按导出配置导: `txt` / `epub`, 图片书为 `cbz`。 */
        data class ByConfig(val type: Int) : ExportMode

        /** 自定义导出: 章节下标集合 + 分卷大小 (epub)。 */
        data class CustomEpub(val scope: Set<Int>, val size: Int) : ExportMode
    }

    /** 导出进度通知正文 (对照 app 端 ExportBookService.notificationContentText)。 */
    private var notificationContentText = syncGetString("service_starting")

    override val capabilityScope: CoroutineScope get() = scope

    /**
     * 保存日志到备份目录 (对照原版 `AboutFragment.saveLog` 的三段式: 未设备份目录则提示中止、
     * 未开记录日志只提醒、写成功后提示"已保存至备份目录")。
     *
     * 打包与落盘见 nativeMain 的 [NativeSaveLog] —— 它要用备份目录的授权通道
     * (`copyBackupIntoUserDir`), 那个声明在 nativeMain, 本源集可见。
     */
    override fun saveLog() {
        NativeSaveLog.run(capabilityScope, AppLog.isRecordLogEnabled)
    }

    override fun onHttpTtsEdited(engine: HttpTTS) {
        if (IosReadAloudHost.isRun && defaultTtsEngineConfig() == engine.id.toString()) {
            // 正在播放则重载；暂停时记录重建位置，继续朗读才启用新配置。
            IosReadAloudHost.play(play = !IosReadAloudHost.isPause)
        }
    }

    // iOS 无物理/手势返回键, PlatformBackHandler 是 no-op → 无系统返回通道,
    // 页面内的子状态需自带可见退出口。
    override val supportsSystemBack: Boolean get() = false

    /**
     * 关于页「崩溃日志」的 iOS 行为: 先直接跳到系统「分析数据」页, 跳不成再弹对话框。
     *
     * # 为何不打开应用自己的崩溃日志列表
     * iOS 侧应用只能拿到带函数名的异常日志 (Kotlin/ObjC 异常); 原生崩溃 (如换源闪退)
     * 的完整调用栈只有系统报告有 —— 信号处理器内不允许取调用栈, 自己落盘的现场只有裸地址,
     * 连崩在哪个模块都判不出。所以这个入口应当把用户送到那份能定位的报告。
     *
     * # 为何先用深链再回落
     * 系统报告的归档页在设置里要逐级翻 (分析与改进 → 分析数据), 而 iOS 没有公开 API 能直达
     * 该页 —— 公开的 `openSettingsURLString` 只能到本应用自己的设置页。这里用系统仍认的
     * `prefs:` 深链直达; 它是未公开接口, 随系统版本可能失效, 故**必须**带回落:
     * 打不开时弹对话框说明取件路径, 不让用户碰到“点了没反应”。同理, 页面深层路径在不同
     * 小版本上有差异, 故先试“分析数据”深层, 再退一级试“分析与改进”。
     *
     * # 上架前必须先处理这里 (阻塞项)
     * `App-prefs:` 是**未公开 URL scheme**。 App Store 审核指南第 2.5.1 条原文:
     * “Apps may only use public APIs and must run on the appropriate operating system.”
     * 该条将未公开 scheme 一并纳入管控, 审核会据此驳回。
     *
     * 上架时的处理办法 (二选一, 不要只删深链而把入口变成点了没反应):
     * - 删掉 [CRASH_DEEP_LINKS] 里的两条深链, 让入口直接走 [showCrashLogFallback] 的说明框; 或
     * - 换成公开的 `UIApplication.openSettingsURLString` 跳本应用设置页, 再接说明框。
     * 两种改法都无需改调用方 —— 回落链路已存在, 去掉深链即自然落到说明框。
     */
    override fun showCrashLogs() {
        tryDeepLink(CRASH_DEEP_LINKS) { showCrashLogFallback() }
    }

    /**
     * 待试的系统报告取件深链 (按页层深度排序, 逐个试)。
     *
     * 均为未公开 scheme, 上架前必须移除 —— 理由与改法见 [showCrashLogs] 的上架说明。
     */
    private val CRASH_DEEP_LINKS = listOf(
        // 分析与改进 → 分析数据 (目标页)
        "App-prefs:root=Privacy&path=PROBLEM_REPORTING/DIAGNOSTIC_USAGE_DATA",
        // 退一级: 分析与改进 (再手动点进“分析数据”)
        "App-prefs:root=Privacy&path=PROBLEM_REPORTING",
    )

    /**
     * 依次试 [urls], 任一被系统接受即止; 全不接受时调 [onAllFailed]。
     *
     * `openURL` 的 completionHandler 回调线程不保证是主线程, 故后续动作统一回主线程。
     *
     * 纯机制函数, 不含任何未公开 scheme —— 上架前只需处理 [CRASH_DEEP_LINKS]。
     */
    private fun tryDeepLink(urls: List<String>, onAllFailed: () -> Unit) {
        fun attempt(index: Int) {
            if (index >= urls.size) {
                dispatch_async(dispatch_get_main_queue()) { onAllFailed() }
                return
            }
            dispatch_async(dispatch_get_main_queue()) {
                openURLIfPossible(urls[index]) { accepted ->
                    if (!accepted) attempt(index + 1)
                }
            }
        }
        attempt(0)
    }

    /**
     * 深链不可用时的人工取件指引。
     *
     * 用户可感知的后果: 告诉他报告在“设置 → 隐私与安全性 → 分析与改进 → 分析数据”里,
     * 找以应用名开头、时间与崩溃时刻一致的文件。
     */
    private fun showCrashLogFallback() {
        val rootVc = topMostViewController() ?: run {
            Toasters.get().toast(CRASH_REPORT_HINT)
            return
        }
        val alert = UIAlertController.alertControllerWithTitle(
            title = syncGetString("crash_log"),
            message = CRASH_REPORT_HINT,
            preferredStyle = UIAlertControllerStyleAlert,
        )
        alert.addAction(
            UIAlertAction.actionWithTitle(
                title = syncGetString("ok"),
                style = UIAlertActionStyleDefault,
                handler = { _ -> },
            )
        )
        try {
            rootVc.presentViewController(alert, animated = true, completion = null)
        } catch (_: Exception) {
            Toasters.get().toast(CRASH_REPORT_HINT)
        }
    }

    private val appDb get() = AppDbProviders.get()
    private val prefs get() = PreferenceProviders.get()

    /** 书源分组增删改 (shared 下沉件)。 */
    private val bookSourceViewModel by lazy { BookSourceViewModelShared(scope) }

    // iOS 由系统统一管理应用生命周期, 无 Activity.finish 等价物
    override fun exitApplication() = Unit

    // ===== 全局转场动画平台 spec (方案 A: 动画单一注入点参数化) =====
    // iOS: UINavigationController push/pop 转场语义 (系统无动画时长缩放配置可动态读取,
    // 用平台规范默认值): 350ms + Core Animation kCAMediaTimingFunctionEaseInEaseOut
    // (0.42,0,0.58,1); 前进=新页全宽滑入+旧页左移 30%, 返回=目标页自左 30% 滑回+
    // 出栈页全宽滑出; 系统 push/pop 转场不带 fade, 保持现状无淡入淡出。
    override val routeTransitionSpec: RouteTransitionSpec
        get() = RouteTransitionSpec(
            pushDurationMillis = 350,
            pushEasing = TransitionEasing.CubicBezier(0.42f, 0f, 0.58f, 1f),
            newPageSlideFraction = 1f,
            oldPageShiftFraction = 0.3f,
            newPageFadeIn = false,
            oldPageFadeOut = false,
            newPageScaleFrom = 1f,
            popDurationMillis = 350,
            popEasing = TransitionEasing.CubicBezier(0.42f, 0f, 0.58f, 1f),
            targetPageSlideFraction = 0.3f,
            outgoingSlideFraction = 1f,
            targetPageFadeIn = false,
            outgoingFadeOut = false,
            targetPageScaleFrom = 1f,
        )

    // iOS 无系统对话框动画规范可循, 沿用 shared 默认 (Android 系统 dialog 动画资源语义 200/150ms)
    override val dialogTransitionSpec: DialogTransitionSpec
        get() = DefaultDialogTransitionSpec

    override fun openExternalUrl(url: String) {
        openURL(url)
    }

    // AVAudioSession 真按 exclusive 切 MixWithOthers (见 IosMediaNotificationController
    // .setAudioFocus → applyAudioSession), 所以"忽略音频焦点"开关在本端真实生效
    override val audioFocusSupported: Boolean get() = true

    override fun shareText(text: String) {
        presentShareSheet(listOf(text))
    }

    override fun copyToClipboard(text: String) {
        copyTextToClipboard(text)
    }

    // 读系统剪贴板 (对照原版 ContextExtensions getClipText: 主题导入/规则粘贴等 7 场景)
    override fun getClipboardText(): String? = readFromClipboard()

    /**
     * 导出分发「上传 URL」: 走 commonMain 下沉的 [upLoadToDirectLink] (压缩/上传/解析直链
     * 四端同一份), 平台文件载荷与压缩由 [DirectLinkUploadNativePrimitives] 注入。失败路径也
     * 必须回调 (传 null) —— 导出对话框的防重复点击标志靠这一次回调解开, 不回调就是整个对话框
     * 永久点不动。
     */
    override fun upLoadFile(
        fileName: String,
        file: Any,
        contentType: String,
        onResult: (String?) -> Unit,
    ) {
        Coroutine.async {
            upLoadToDirectLink(DirectLinkUploadNativePrimitives, fileName, file, contentType)
        }
            .onSuccess { onResult(it) }
            .onError { error ->
                AppLog.put("上传文件失败\n${error.message}", error)
                Toasters.get().toast("上传文件失败\n${error.message}")
                onResult(null)
            }
    }

    // 完整分发链 (压缩包/JSON 一键导入/书籍文件) 见 FileAssociationDispatch, 四端共用
    override fun openImportFile(filePath: String) {
        scope.launch { FileAssociationDispatch.dispatch(filePath) }
    }

    // ===== 导入本地书 (状态与扫描见 NativeImportBook, 与鸿蒙共用) =====

    // 上次导入目录的授权经 minimal bookmark 恢复 (security-scoped URL 现在可跨启动解析),
    // 恢复失败则保持原行为: 等用户在导入页重新选目录 (restoreLast=false)
    override suspend fun initImportBookData(): Boolean {
        val restored = IosSecurityScopedStorage.restoreImportRootPath()
        NativeImportBook.init(restoreLast = false)
        restored?.let { NativeImportBook.setRoot(it) }
        return NativeImportBook.needsRootDir
    }

    override fun importBookItems(): StateFlow<List<ImportFileItem>> = NativeImportBook.items
    override fun importBookPath(): StateFlow<String?> = NativeImportBook.path
    override fun importBookLoading(): StateFlow<Boolean> = NativeImportBook.loading
    override fun importBookEmptyMsgVisible(): StateFlow<Boolean> =
        NativeImportBook.emptyMsgVisible

    override fun importBookHasParentDir(): StateFlow<Boolean> = NativeImportBook.hasParentDir

    // 对照 Android onPickFolder / selectFolder.launch。
    // 不用 IosFilePickerService.pickDirectory (只返回 path, 会丢 security-scoped URL):
    // 这里直接拿 NSURL 交授权表, 否则读取授权目录内容会失败
    override fun pickImportFolder() {
        scope.launch {
            val url = pickDirectoryDocument() ?: return@launch
            // 授权表负责: 释放上一目录 scope → 起新 scope → 落 bookmark (换目录不留悬空授权)
            val path = IosSecurityScopedStorage.setImportRoot(url) ?: return@launch
            NativeImportBook.setRoot(path)
        }
    }

    override fun scanImportFolder() = NativeImportBook.scan()

    // ===== 书籍目录授权 (对照 app 端 SAF: OtherConfigHost.localBookTreeSelect / BaseReadBookActivity.selectBookFolderResult) =====

    /**
     * 选书籍目录: 直接拿 UIDocumentPicker(Open) 的 security-scoped NSURL, 不经过只回 path 的
     * [PlatformServices.FilePickerService] —— 普通路径在下次访问时拿不回授权。
     * 选中后存 minimal bookmark (可跨启动) 并记下用途 [DirUsage.BookTree], 回调仍传目录路径
     * (与其余端同一契约)。
     */
    override fun pickBookTreeUri(onSelected: (String?) -> Unit) {
        scope.launch {
            val url = pickDirectoryDocument()
            if (url == null) {
                onSelected(null)
                return@launch
            }
            val path = IosSecurityScopedStorage.addDir(url, DirUsage.BookTree)
            onSelected(path)
        }
    }

    /**
     * 在已授权目录里按文件名找回本地书 (对照原版 `FileDoc.find(book.originName)` 默认 depth=0:
     * 只查所选目录, 与 app 端一致); 返回 `file://` + 绝对路径, 与导入写入的 bookUrl 同格式。
     */
    override fun findBookFileInDir(dirUri: String, fileName: String): String? =
        IosSecurityScopedStorage.findBookFileInTree(dirUri, fileName)

    // 对照 Android alertImportFileName: 复用现有 UIAlertController 文本输入弹窗
    // (presentTextInput), 允许清空 = 恢复默认文件名解析。预设值存 PreferKey.bookImportFileName。
    override fun alertImportFileName() {
        presentTextInput(
            title = "按文件名导入",
            message = "使用js处理文件名变量src，将书名作者分别赋值到变量name author",
            hint = "js",
            initial = prefs.getString(PreferKey.bookImportFileName, ""),
            allowEmpty = true,
        ) {
            prefs.putString(PreferKey.bookImportFileName, it)
        }
    }

    // 压缩包导入需要书籍保存位置: 未设置时经既有"书籍保存位置"选择器选定后继续导入
    override fun addImportSelectionToBookshelf(
        items: List<ImportFileItem>,
        onComplete: () -> Unit,
    ) = NativeImportBook.addToBookshelf(
        items,
        onComplete,
        onNeedBooksDir = { ensureBookSaveDirSelected() },
    )

    override fun updateImportBookFilter(key: String) = NativeImportBook.updateFilter(key)

    override fun updateImportBookSort(sort: Int) = NativeImportBook.updateSort(sort)

    override fun openImportedBookReader(item: ImportFileItem) = NativeImportBook.openReader(item)

    override fun navigateImportDir(item: ImportFileItem) = NativeImportBook.enterDir(item)

    override fun goBackImportDir() {
        NativeImportBook.goBack()
    }

    // 按 bookUrl 查 DB 解析 BookRef, 供 deep link / 文件关联的路由导航
    override suspend fun resolveBookRef(bookUrl: String): BookRef? =
        appDb.bookDao.getBook(bookUrl)?.toRouteRef()

    // 换桌面图标 (对照 app 端 LauncherIconHelp.changeIcon / Android setComponentEnabledSetting):
    // iOS 用 UIApplication.setAlternateIconName 切换 bundle 内交替图标
    // (Info.plist/project.yml 的 CFBundleAlternateIcons 已声明 Icon1/Icon4/Icon5,
    // 对应 iosApp/Icon1.png / Icon4.png / Icon5.png); 传 nil = 恢复主图标 (AppIcon.png)。
    // 系统要求主线程调用, completionHandler 传 null (错误仅落系统日志)。
    override fun changeLauncherIcon(icon: String) {
        val alternateName = when (icon) {
            "launcher1" -> "Icon1"
            "launcher4" -> "Icon4"
            "launcher5" -> "Icon5"
            else -> null // ic_launcher = 默认主图标
        }
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.setAlternateIconName(alternateName, null)
        }
    }

    override val launcherIconChangeSupported: Boolean get() = true

    override fun getAppVersionName(): String? =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String

    // 当前是否横屏: UIScreen.bounds 随界面方向变化 (nativeBounds 恒竖屏基准, 不能用)
    override fun isLandscape(): Boolean =
        UIScreen.mainScreen.bounds.useContents { size.width > size.height }

    // 对照 ChapterProvider.upLayout 的 appCtx.isPad ("平板/横屏双页" auto 分支);
    // iOS 用 UIDevice.userInterfaceIdiom 判定 (iPad 专用/通用应用在 iPad 上为 Pad)
    override fun isTablet(): Boolean =
        UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad

    // ===== Web 服务 =====

    /**
     * iOS 无 Web 服务 (没有后台常驻能力, 服务只能在前台期间存活), 「我的」页的开关按能力门禁
     * 隐藏 (见 `WebServerPlatforms.getOrNull` 判定)。本覆写兜住"开关之外仍被调用"的路径:
     * iOS 未注册 [io.legado.app.web.WebServerPlatforms] 实现, 落到共享基类会抛错被静默记日志。
     */
    override fun setWebService(enabled: Boolean) = unsupported("网络服务")

    // ===== 关于页 =====

    // ===== 书籍详情页 =====

    // 本地书文件字节数: 外部目录下的书籍文件走 security-scoped 授权读取属性
    // (bookUrl 形如 file:///path; 沙盒内文件无需授权)
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(Dispatchers.IO) {
        val path = bookUrl.removePrefix("file://")
        runCatching { IosSecurityScopedStorage.withAccess(path, DirUsage.BookTree) { File(it).length() } }
            .getOrDefault(0L)
    }

    // ===== webFile 下载导入 (对照 BookInfoActivity 同名方法) =====
    //
    // 平台无关编排 (构建 webFile 列表 / 下载 / 导入本地 / 压缩包条目) 已下沉:
    // 取件与文件名推导在 WebFileImportShared, 下载导入/压缩包导入在 SharedPlatformCapabilities
    // (目录落库统一走 BookChapterLoader.fetchFromSource)。
    // 本端只保留两处 UIKit 形态: 条目选择框与"不支持格式时交系统程序打开"
    // (对照 app 端 `activity.selector` / alert 的 open_fun 分支)。

    /** 对照 BookInfoActivity.onReadClick isWebFile 分支: 构建 webFile 列表后弹条目选择框。 */
    override fun handleWebFileRead(
        book: Book,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
        onSuccess: ((Book) -> Unit)?,
    ) {
        scope.launch {
            // 对照 BaseReadViewModel.loadWebFile: 本地书无书源, 其余按 origin 查源
            val source = if (book.origin != BookType.localTag) {
                appDb.bookSourceDao.getBookSource(book.origin)
            } else null
            val webFiles = WebFileImportShared.buildWebFiles(book, source)
            withContext(Dispatchers.Main) {
                if (webFiles.isEmpty()) {
                    Toasters.get().toast("Unexpected webFileData")
                    return@withContext
                }
                presentWebFileSelect(book, webFiles, onWaitDialog, onAction, onSuccess)
            }
        }
    }

    /** 条目选择框 (对照 app 端 `activity.selector(download_and_import_file, webFiles)`)。 */
    private fun presentWebFileSelect(
        book: Book,
        webFiles: List<FileBook.WebFile>,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
        onSuccess: ((Book) -> Unit)?,
    ) {
        presentSelectList(
            title = syncGetString("download_and_import_file"),
            items = webFiles.map { it.name },
        ) { index ->
            val webFile = index?.let { webFiles.getOrNull(it) } ?: return@presentSelectList
            when {
                webFile.isSupported ->
                    importWebFile(book, webFile, onWaitDialog, onAction) { onSuccess?.invoke(it) }

                webFile.isSupportDecompress ->
                    downloadWebFile(book, webFile, onWaitDialog, onAction) { path ->
                        getArchiveFilesName(path) { fileNames ->
                            if (fileNames.size == 1) {
                                importBookFromArchive(path, fileNames[0], book, onWaitDialog) {
                                    onSuccess?.invoke(it)
                                }
                            } else {
                                presentArchiveEntrySelect(
                                    path, fileNames, book, onWaitDialog, onSuccess,
                                )
                            }
                        }
                    }

                else -> presentUnsupportedWebFileAlert(book, webFile, onWaitDialog, onAction)
            }
        }
    }

    /** 压缩包内选章 (对照 app 端 `showDecompressFileImportAlert`)。 */
    private fun presentArchiveEntrySelect(
        archiveFilePath: String,
        fileNames: List<String>,
        book: Book,
        onWaitDialog: (Boolean) -> Unit,
        onSuccess: ((Book) -> Unit)?,
    ) {
        if (fileNames.isEmpty()) {
            Toasters.get().toast(syncGetString("unsupport_archivefile_entry"))
            return
        }
        presentSelectList(
            title = syncGetString("import_select_book"),
            items = fileNames,
        ) { index ->
            val name = index?.let { fileNames.getOrNull(it) } ?: return@presentSelectList
            importBookFromArchive(archiveFilePath, name, book, onWaitDialog) {
                onSuccess?.invoke(it)
            }
        }
    }

    /** 不支持的格式: 下载后交系统程序打开 (对照 app 端 alert 的 open_fun 分支)。 */
    private fun presentUnsupportedWebFileAlert(
        book: Book,
        webFile: FileBook.WebFile,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
    ) {
        val alert = UIAlertController.alertControllerWithTitle(
            title = syncGetString("draw"),
            message = syncGetString("file_not_supported", webFile.name),
            preferredStyle = UIAlertControllerStyleAlert,
        )
        alert.addAction(
            UIAlertAction.actionWithTitle(
                title = syncGetString("open_fun"),
                style = UIAlertActionStyleDefault,
                handler = { _ ->
                    downloadWebFile(book, webFile, onWaitDialog, onAction) { path ->
                        // iOS 无"交系统程序打开"通道 (UIApplication.openURL 打不开本地文件),
                        // 用系统分享面板把文件交给其它 app (与导出书籍同款出口)
                        presentShareSheet(
                            listOf(NSURL.fileURLWithPath(path.removePrefix("file://")))
                        )
                    }
                },
            )
        )
        alert.addAction(
            UIAlertAction.actionWithTitle(
                title = syncGetString("cancel"),
                style = UIAlertActionStyleCancel,
                handler = { _ -> },
            )
        )
        presentAlert(alert)
    }

    // 对照 RemoteBookActivity.startRead 的 archive 分支: 在书籍保存目录里找已下载的压缩包,
    // 找到就交文件关联分发链, 没找到就弹确认框由调用方重新下载后重试。
    // native 端无"压缩包内选章阅读"UI, 与 NativeImportBook.openReader 同口径走
    // FileAssociationDispatch 的直接导入链 (平台能力上限, 不假装与 app 端等价)
    override fun startReadRemoteArchive(fileName: String, onNeedDownload: () -> Unit) {
        scope.launch {
            // 与 NativeFileBookAccessor 的有效书籍保存目录同源 (用户在"书籍保存位置"选定);
            // 选定目录在沙盒外, 存在性检查同样要包既有授权租约
            val dir = effectiveBookSaveDir()
            if (dir != null) {
                val path = "$dir/$fileName"
                val exists = runCatching {
                    withLocalDirAccess(path) { File(path).exists() }
                }.getOrDefault(false)
                if (exists) {
                    FileAssociationDispatch.dispatch(path)
                    return@launch
                }
            }
            withContext(Dispatchers.Main) {
                val alert = UIAlertController.alertControllerWithTitle(
                    title = syncGetString("draw"),
                    message = syncGetString("archive_not_found"),
                    preferredStyle = UIAlertControllerStyleAlert,
                )
                alert.addAction(
                    UIAlertAction.actionWithTitle(
                        title = syncGetString("ok"),
                        style = UIAlertActionStyleDefault,
                        handler = { _ -> onNeedDownload() },
                    )
                )
                alert.addAction(
                    UIAlertAction.actionWithTitle(
                        title = syncGetString("cancel"),
                        style = UIAlertActionStyleCancel,
                        handler = { _ -> },
                    )
                )
                presentAlert(alert)
            }
        }
    }

    // ===== 书架管理: 导出开关 =====

    // ===== 书架管理: 导出 JSON =====

    /**
     * 更新导出进度通知 (对照 app 端 ExportBookService.upExportNotification)。
     *
     * 通知 id 用 [NotificationId.ExportBookService], 点击后由 IosNotificationService 的
     * id→route 表跳到书架管理页; 同 id 覆盖式更新, 即"同一条通知"从进行中变结束态。
     *
     * @param finish true = 结束态 (对照原版该分支的 setOngoing(false) + setAutoCancel(true)):
     *   通知上不带"取消"动作
     */
    private fun upExportNotification(finish: Boolean = false) {
        val title = syncGetString("export_book")
        if (finish) {
            IosNotificationService.notifyWithoutActions(
                NotificationId.ExportBookService, title, notificationContentText,
            )
            return
        }
        PlatformServiceProviders.get().notifications.notify(
            NotificationId.ExportBookService,
            title,
            notificationContentText,
        )
    }

    /**
     * 取消正在跑的导出并撤掉导出通知 (对照原版 ExportBookService 的 IntentAction.stop 分支:
     * `notificationManager.cancel(NotificationId.ExportBook)` + `stopSelf()` —— 后者使服务的
     * lifecycleScope 取消, 导出协程随之停止)。
     *
     * 由通知上的"取消"动作与"清掉通知"两处经 [IosNotificationDelegate] 调用; 该时机可能落在
     * "应用被杀后由系统在后台拉起"的路径上 (UI 未创建), 通知能力直接取 [IosNotificationService],
     * 不经 [PlatformServiceProviders]。
     */
    internal fun cancelExport() {
        exportBooksJob?.cancel()
        exportBooksJob = null
        IosNotificationService.cancelNotification(NotificationId.ExportBookService)
    }

    // 导出书籍正文, 直接写进有效导出目录 (用户经目录选择器选定; 未选过时先选目录),
    // 不经系统分享面板 (对照 app 端 ExportBookService 的落盘语义)
    override fun exportAllBooks(books: List<Book>) {
        if (books.isEmpty()) {
            Toasters.get().toast("书籍不能为空")
            return
        }
        // 未选过导出目录时先选目录 (对照 BookshelfManageActivity.exportAll)
        if (effectiveBookExportDir() == null) {
            selectExportFolder(books)
            return
        }
        // 导出配置在此快照 (对照原版把 exportType 随 intent 传进服务)
        val exportType = prefs.getInt(PreferKey.exportType, 0)
        enqueueExport(books) { ExportMode.ByConfig(exportType) }
    }

    /**
     * 导出请求入队 (对照原版 BookshelfManageActivity.startExportBooks → ExportBookService:
     * 每本书一条队列项, 重复触发入队稍后照常导出), 队列空闲时顺手拉起执行者。
     *
     * @param label 导出方式后缀 (自定义章节导出提示里带"自定义章节", 普通导出为空)
     * @param modeOf 按书算导出方式 (图片书一律 cbz 等)
     */
    private fun enqueueExport(books: List<Book>, label: String = "", modeOf: (Book) -> ExportMode) {
        if (books.isEmpty()) return
        val (added, started) = synchronized(exportLock) {
            // 同一本书只入队一次 (正在导出的那本不入队), 已在队列里则换成最新导出方式
            var count = 0
            books.forEach { book ->
                if (book.bookUrl == exportingBookUrl) return@forEach
                waitExportBooks[book.bookUrl] = PendingExport(book, modeOf(book))
                count++
            }
            val start = count > 0 && !exportWorkerRunning
            if (start) exportWorkerRunning = true
            count to start
        }
        if (added == 0) return
        Toasters.get().toast(
            if (started) "开始导出 $added 本$label" else "已加入导出队列 $added 本$label"
        )
        if (started) exportBooksJob = scope.launch { runExportQueue() }
    }

    /** 取队首的一本书并标记"正在导出" (对照原版 export(): 取队首 → exportProgress 置位), 空队列返回 null。 */
    private fun takeNextExport(): PendingExport? = synchronized(exportLock) {
        val entry = waitExportBooks.entries.firstOrNull() ?: return null
        waitExportBooks.remove(entry.key)
        exportingBookUrl = entry.key
        entry.value
    }

    /** 队列里还剩几本待导。 */
    private fun pendingExportCount(): Int = synchronized(exportLock) { waitExportBooks.size }

    /** 当前这本导完, 清掉"正在导出"标记。 */
    private fun finishExport() = synchronized(exportLock) { exportingBookUrl = null }

    /** 队列空时收工 (与 [enqueueExport] 同锁): 确认没新入队才真收工, 否则继续导。 */
    private fun finishWorkerIfQueueEmpty(): Boolean = synchronized(exportLock) {
        if (waitExportBooks.isNotEmpty()) {
            false
        } else {
            exportWorkerRunning = false
            true
        }
    }

    /** 取消时丢掉剩余队列并归还执行者名额 (对照原版 IntentAction.stop → stopSelf: 未导出的不再导出)。 */
    private fun discardExportQueue() = synchronized(exportLock) {
        waitExportBooks.clear()
        exportingBookUrl = null
        exportWorkerRunning = false
    }

    /** 导出 [pending] 这一本, 返回写出的文件 (对照原版 ExportBookService 按 ExportConfig 分流)。 */
    private suspend fun exportPending(
        pending: PendingExport,
        exportDir: File,
        usedPaths: MutableSet<String>,
    ): List<File> = when (val mode = pending.mode) {
        is ExportMode.ByConfig -> listOf(
            NativeBookExporter.exportBookFile(pending.book, exportDir, usedPaths, mode.type)
        )

        is ExportMode.CustomEpub -> if (pending.book.isImage) {
            listOf(NativeBookExporter.exportBookFile(pending.book, exportDir, usedPaths, 1))
        } else {
            val chapters = appDb.bookChapterDao.getChapterList(pending.book.bookUrl)
            val selected = chapters.filterIndexed { index, _ -> index in mode.scope }
            if (selected.isEmpty()) error("章节范围内没有章节")
            selected.chunked(mode.size).mapIndexed { index, part ->
                NativeBookExporter.exportBookFile(
                    pending.book, exportDir, usedPaths, 1, part,
                    pending.book.getExportFileName("epub", index + 1),
                )
            }
        }
    }

    /**
     * 串行导出队列 (对照原版 ExportBookService.export(): 逐本从队首取出, 导完一本再取下一本)。
     * 单本出错只记日志并继续下一本; 通知取消则丢掉剩余队列, 与 app 端 stopSelf 同语义。
     */
    private suspend fun runExportQueue() {
        val failures = mutableListOf<String>()
        val usedPaths = mutableSetOf<String>()
        var exported = 0
        try {
            while (true) {
                val pending = takeNextExport()
                if (pending == null) {
                    if (finishWorkerIfQueueEmpty()) break
                    continue
                }
                try {
                    currentCoroutineContext().ensureActive()
                    val opened = openExportDir()
                    if (opened == null) {
                        // 目录不可用 (已提示并忘掉记忆目录): 本次不能导出, 剩余队列不再导
                        discardExportQueue()
                        break
                    }
                    val (exportDir, lease) = opened
                    try {
                        notificationContentText = syncGetString(
                            "export_book_notification_content",
                            pending.book.name,
                            pendingExportCount(),
                        )
                        upExportNotification()
                        exportPending(pending, exportDir, usedPaths).forEach { file ->
                            exported++
                            if (prefs.getBoolean(PreferKey.exportToWebDav, false)) {
                                AppWebDavShared.exportWebDav(file.absolutePath, file.name)
                            }
                        }
                    } finally {
                        lease.close()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures.add("${pending.book.name}：${e.message ?: "未知错误"}")
                    AppLog.put("导出书籍失败：${pending.book.name}\n${e.message}", e)
                } finally {
                    finishExport()
                }
            }
        } catch (e: CancellationException) {
            // 通知取消导出: 不报错, 已写出的半成品与 app 端取消语义一致; 剩余队列不再导出
            discardExportQueue()
            throw e
        } catch (e: Throwable) {
            // 意外异常不能把执行者名额带走, 否则后续导出会一直排队没人导
            discardExportQueue()
            throw e
        }
        notificationContentText = "导出完成"
        upExportNotification(finish = true)
        if (exported > 0) {
            Toasters.get().toast("导出完成\n可在「文件」App 中查看")
        }
        if (failures.isNotEmpty()) {
            Toasters.get().toast("${failures.size} 本书导出失败：${failures.take(2).joinToString()}")
        }
        if (exported > 0 && failures.isNotEmpty()) {
            AppLog.put("部分书籍导出失败：${failures.joinToString()}")
        }
    }

    /**
     * 选择导出文件夹 (对照 BookshelfManageActivity.selectExportFolder 与 exportDir 回调):
     * 选定目录存 security-scoped bookmark 并记入设备本地配置空间 ([rememberBookExportDir]);
     * 开了自定义导出 (且导出类型为 epub) 先弹章节配置, 否则立即导出。
     *
     * 与 [pickBookTreeUri] 同一入口 ([pickDirectoryDocument]) 但用途不同: 这里登记为
     * [DirUsage.Export], 该目录只放行导出写入, 不放行书籍文件访问。
     */
    override fun selectExportFolder(books: List<Book>) {
        scope.launch {
            val url = pickDirectoryDocument() ?: return@launch
            val path = IosSecurityScopedStorage.addDir(url, DirUsage.Export) ?: run {
                Toasters.get().toast("无法保存导出目录的访问授权")
                return@launch
            }
            rememberBookExportDir(path)
            if (books.isEmpty()) return@launch
            // 本类同名成员是"自定义导出"开关当前值 (菜单勾选态), 走全限定名取上面那条带导出类型的判定
            if (io.legado.app.utils.enableCustomExport()) {
                showExportSectionConfig(path, books)
            } else {
                exportAllBooks(books)
            }
        }
    }

    /**
     * 打开有效导出目录: 取 security-scoped 授权租约 + 确保目录存在, 供整段导出持有。
     *
     * 用户选定的目录在应用容器之外, 直接按 POSIX 路径写入会因缺 security scope 失败;
     * 写入新建文件与 [io.legado.app.model.fileBook.IosAuthorizedFileBookAccessor] 的落盘入口
     * 同模式 —— 直接持 [IosSecurityScopedStorage.acquireLease] 而不走 NSFileCoordinator
     * (协调器会对尚不存在的目标路径报错)。
     *
     * 授权已失效 (用户在系统里撤销/换过目录) 时清掉记忆目录 ([rememberBookExportDir]), 下次导出
     * 重新让用户选目录 (与图片保存目录写入失败即忘掉记忆目录同一处理)。
     *
     * @return 目录与租约 (调用方必须在 finally 里 [IosSecurityScopeLease.close]);
     *   已提示并返回 null 表示本次不能导出
     */
    private fun openExportDir(): Pair<File, IosSecurityScopeLease>? {
        val path = effectiveBookExportDir() ?: return null
        val lease = try {
            IosSecurityScopedStorage.acquireLease(path, DirUsage.Export)
        } catch (e: Exception) {
            rememberBookExportDir(null)
            AppLog.put("导出目录不可用, 已清除记忆目录\n${e.message}", e)
            Toasters.get().toast("导出目录不可用，请重新选择")
            return null
        }
        val dir = File(path)
        if (!dir.isDirectory && !dir.mkdirs()) {
            lease.close()
            Toasters.get().toast("无法创建导出目录")
            return null
        }
        return dir to lease
    }

    override fun showExportSectionConfig(path: String, books: List<Book>) {
        if (books.isEmpty()) {
            Toasters.get().toast("书籍不能为空")
            return
        }
        dispatch_async(dispatch_get_main_queue()) {
            val vc = topMostViewController() ?: return@dispatch_async
            val dialog = UIAlertController.alertControllerWithTitle(
                "自定义 EPUB 导出", "章节范围如 1-5,8；每份至少 1 章。图片书仍导出 CBZ。", UIAlertControllerStyleAlert,
            )
            dialog.addTextFieldWithConfigurationHandler { field ->
                field?.placeholder = "章节范围，例如 1-5,8"
            }
            dialog.addTextFieldWithConfigurationHandler { field ->
                field?.placeholder = "每份 EPUB 的章节数"
                field?.text = "1"
            }
            dialog.addTextFieldWithConfigurationHandler { field ->
                field?.placeholder = "分卷文件名 JS 表达式，留空用默认名称"
                field?.text = prefs.getString(PreferKey.episodeExportFileName, "")
            }
            dialog.addAction(UIAlertAction.actionWithTitle("导出全部", UIAlertActionStyleDefault) {
                exportAllBooks(books)
            })
            dialog.addAction(UIAlertAction.actionWithTitle("按范围导出", UIAlertActionStyleDefault) {
                val fields = dialog.textFields
                val range = (fields?.getOrNull(0) as? UITextField)?.text.orEmpty().trim()
                val size = (fields?.getOrNull(1) as? UITextField)?.text.orEmpty().toIntOrNull()
                val fileNameRule = (fields?.getOrNull(2) as? UITextField)?.text.orEmpty().trim()
                val scope = runCatching { ExportBookUtils.parseScope(range) }.getOrNull()
                if (scope.isNullOrEmpty() || scope.any { it < 0 } || size == null || size < 1) {
                    Toasters.get().toast("章节范围或每份章节数无效")
                    return@actionWithTitle
                }
                prefs.putString(PreferKey.episodeExportFileName, fileNameRule)
                exportCustomBooks(books, scope, size)
            })
            dialog.addAction(UIAlertAction.actionWithTitle("取消", UIAlertActionStyleCancel, null))
            vc.presentViewController(dialog, animated = true, completion = null)
        }
    }

    private fun exportCustomBooks(books: List<Book>, scopeChapters: Set<Int>, size: Int) {
        if (books.isEmpty()) return
        if (effectiveBookExportDir() == null) {
            selectExportFolder(books)
            return
        }
        enqueueExport(books, label = " (自定义章节)") { ExportMode.CustomEpub(scopeChapters, size) }
    }

    override fun toggleExportUseReplace() {
        val enabled = !prefs.getBoolean(PreferKey.exportUseReplace, true)
        prefs.putBoolean(PreferKey.exportUseReplace, enabled)
    }

    override fun exportUseReplace(): Boolean = prefs.getBoolean(PreferKey.exportUseReplace, true)

    override fun showExportConfig() {
        dispatch_async(dispatch_get_main_queue()) {
            val vc = topMostViewController() ?: return@dispatch_async
            val current = if (prefs.getInt(PreferKey.exportType, 0) == 1) "EPUB" else "TXT"
            val dialog = UIAlertController.alertControllerWithTitle(
                "导出配置", "当前格式：$current。图片书始终导出 CBZ。文件名可用 name、author 的 JS 表达式；留空使用默认名称。", UIAlertControllerStyleAlert,
            )
            dialog.addTextFieldWithConfigurationHandler { field ->
                field?.placeholder = "导出文件名表达式"
                field?.text = prefs.getString(PreferKey.bookExportFileName, "")
            }
            dialog.addTextFieldWithConfigurationHandler { field ->
                field?.placeholder = "TXT 字符集，例如 UTF-8 或 GBK"
                field?.text = prefs.getString(PreferKey.exportCharset, "UTF-8")
            }
            listOf(
                Triple("TXT · 含章节名", 0, false),
                Triple("TXT · 不含章节名", 0, true),
                Triple("EPUB", 1, false),
            ).forEach { (label, value, noChapterName) ->
                dialog.addAction(UIAlertAction.actionWithTitle("保存为 $label", UIAlertActionStyleDefault) {
                    val fileName = (dialog.textFields?.getOrNull(0) as? UITextField)?.text.orEmpty().trim()
                    val charset = (dialog.textFields?.getOrNull(1) as? UITextField)?.text.orEmpty().trim()
                        .ifEmpty { "UTF-8" }
                    if (runCatching { textCharsetCodec(charset) }.isFailure) {
                        Toasters.get().toast("不支持的 TXT 字符集：$charset")
                        return@actionWithTitle
                    }
                    prefs.putString(PreferKey.bookExportFileName, fileName)
                    prefs.putString(PreferKey.exportCharset, charset)
                    prefs.putInt(PreferKey.exportType, value)
                    if (value == 0) prefs.putBoolean(PreferKey.exportNoChapterName, noChapterName)
                })
            }
            dialog.addAction(UIAlertAction.actionWithTitle("取消", UIAlertActionStyleCancel, null))
            vc.presentViewController(dialog, animated = true, completion = null)
        }
    }

    override fun exportAllUseBookSource() {
        scope.launch {
            val sources = runCatching { appDb.bookDao.getAllUseBookSource() }.getOrElse {
                Toasters.get().toast("导出所用书源失败\n${it.message}")
                return@launch
            }
            exportJson("bookSource.json", GSON.toJson(sources))
        }
    }

    // 导出书架 JSON (字段清单与 app 端 exportBookshelf 一致)
    override fun exportBookshelf(books: List<Book>) {
        if (books.isEmpty()) {
            Toasters.get().toast("书籍不能为空")
            return
        }
        scope.launch { exportJson("bookshelf.json", GSON.toJson(books.map { it.toShelfJsonMap() })) }
    }

    // ===== 书源管理 =====

    override fun exportBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean,
        sort: BookSourceSort
    ) {
        scope.launch {
            val json = selectedSourcesJson(selection, allCount, sortAscending, sort) ?: return@launch
            exportJson("bookSource.json", json)
        }
    }

    override fun selectionAddToGroups(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        presentTextInput(title = "添加分组", hint = "分组名") { group ->
            bookSourceViewModel.selectionAddToGroups(selection, group)
        }
    }

    override fun selectionRemoveFromGroups(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        presentTextInput(title = "移除分组", hint = "分组名") { group ->
            bookSourceViewModel.selectionRemoveFromGroups(selection, group)
        }
    }

    // 关键词输入与校验设置中性钮走共享对话框 (与 Android 逐字一致; 平台侧只负责启动)
    override fun checkBookSource(selection: List<BookSourcePart>) {
        showCheckBookSourceDialog(selection)
    }

    override fun startCheckBookSource(urls: List<String>) {
        IosCheckSource.startUrls(urls)
    }

    // 校验中重进书源管理页 (对照 app 端 CheckSourceService 的 IntentAction.resume: 重发一次进度,
    // 进度事件与通知一并恢复; 共享路由靠该事件点亮进度条)
    override fun resumeCheckSource() {
        IosCheckSource.resume()
    }

    override fun cancelCheckSource() {
        IosCheckSource.stop()
        Debug.finishChecking()
    }

    // ===== 其它设置 =====

    // 清 WKWebView 站点数据 (对照 app 端删 webview 目录; iOS 无进程重启需求, 清完即生效)
    override fun clearWebViewData() {
        val store = WKWebsiteDataStore.defaultDataStore()
        store.removeDataOfTypes(
            dataTypes = WKWebsiteDataStore.allWebsiteDataTypes(),
            modifiedSince = NSDate.dateWithTimeIntervalSince1970(0.0),
        ) {
            Toasters.get().toast("已清理 WebView 数据")
        }
    }

    // ===== 私有辅助 =====

    /** 经 [exportFile] 弹系统"文件"面板导出 (用户可存到本机/iCloud/第三方网盘)。 */
    private suspend fun exportJson(defaultName: String, json: String) {
        val saved = runCatching { exportFile(defaultName, json.encodeToByteArray()) }
            .getOrElse {
                Toasters.get().toast("导出失败\n${it.message}")
                return
            }
        if (saved) Toasters.get().toast("已导出 $defaultName")
    }

    /**
     * 单选列表弹窗 (webFile/压缩包条目选择框): 选中回调传条目下标, 取消或拿不到宿主传 null。
     *
     * iOS 无 Android 的 selector 控件, 用 UIAlertController 的多个 action 表达 (条目多时系统自带滚动);
     * 不用 ActionSheet 形态: iPad 上 ActionSheet 需要 popover 锚点, 无锚点会崩。
     */
    private fun presentSelectList(
        title: String,
        items: List<String>,
        onSelected: (Int?) -> Unit,
    ) {
        val alert = UIAlertController.alertControllerWithTitle(
            title = title,
            message = null,
            preferredStyle = UIAlertControllerStyleAlert,
        )
        items.forEachIndexed { index, item ->
            alert.addAction(
                UIAlertAction.actionWithTitle(
                    title = item,
                    style = UIAlertActionStyleDefault,
                    handler = { _ -> onSelected(index) },
                )
            )
        }
        alert.addAction(
            UIAlertAction.actionWithTitle(
                title = syncGetString("cancel"),
                style = UIAlertActionStyleCancel,
                handler = { _ -> onSelected(null) },
            )
        )
        presentAlert(alert)
    }

    /** 在顶层 vc 上弹 UIAlertController (拿不到宿主时静默放弃, 与 [presentTextInput] 同语义)。 */
    private fun presentAlert(alert: UIAlertController) {
        dispatch_async(dispatch_get_main_queue()) {
            val vc = topMostViewController() ?: return@dispatch_async
            vc.presentViewController(alert, animated = true, completion = null)
        }
    }

    /**
     * 云端有新备份时的确认框 (标题/正文与 app 端 `MainActivity.backupSync` 的
     * `alert(R.string.restore, R.string.webdav_after_local_restore_confirm)` 逐字同源)。
     *
     * 判定不在本方法内: 调用方拿到
     * [io.legado.app.help.StartupMaintenanceShared.NewBackupCheckResult.NewBackup] 后才调本方法,
     * 点确定在 IO 作用域内走共享恢复入口 [AppWebDavShared.restoreWebDav] (与 app 端
     * `MainViewModel.restoreWebDav` 同一个入口), 失败与 app 端同一口径落日志 + toast。
     *
     * @param name 云端备份文件名 (判定结果携带的 `displayName`)
     */
    fun presentWebDavNewBackupConfirm(name: String) {
        val alert = UIAlertController.alertControllerWithTitle(
            title = syncGetString("restore"),
            message = syncGetString("webdav_after_local_restore_confirm"),
            preferredStyle = UIAlertControllerStyleAlert,
        )
        alert.addAction(
            UIAlertAction.actionWithTitle(
                title = syncGetString("ok"),
                style = UIAlertActionStyleDefault,
                handler = { _ ->
                    scope.launch {
                        try {
                            AppWebDavShared.restoreWebDav(name)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 对照 app 端 restoreWebDav 的 onError (AppLog + toastOnUi),
                            // 与共享 BackupConfigRoute 恢复分支同一文案
                            AppLog.put("WebDav恢复出错\n${e.message}", e)
                            Toasters.get().toast("WebDav恢复出错\n${e.message}")
                        }
                    }
                },
            )
        )
        alert.addAction(
            UIAlertAction.actionWithTitle(
                title = syncGetString("cancel"),
                style = UIAlertActionStyleCancel,
                handler = { _ -> },
            )
        )
        presentAlert(alert)
    }

    /**
     * 单行文本输入弹窗 (对照 desktop DesktopDialogRequest.TextInput), 默认空输入不回调。
     *
     * @param allowEmpty true 时空串也回调 (文件名导入 js 场景需要支持清空=恢复默认)。
     */
    private fun presentTextInput(
        title: String,
        hint: String,
        initial: String = "",
        message: String? = null,
        allowEmpty: Boolean = false,
        onConfirm: (String) -> Unit,
    ) {
        dispatch_async(dispatch_get_main_queue()) {
            val vc = topMostViewController() ?: return@dispatch_async
            val alert = UIAlertController.alertControllerWithTitle(
                title = title,
                message = message,
                preferredStyle = UIAlertControllerStyleAlert,
            )
            alert.addTextFieldWithConfigurationHandler { field ->
                field?.placeholder = hint
                field?.text = initial
            }
            alert.addAction(
                UIAlertAction.actionWithTitle(
                    title = "取消",
                    style = UIAlertActionStyleCancel,
                    handler = { _ -> },
                )
            )
            alert.addAction(
                UIAlertAction.actionWithTitle(
                    title = "确认",
                    style = UIAlertActionStyleDefault,
                    handler = { _ ->
                        val text = (alert.textFields?.firstOrNull() as? UITextField)?.text.orEmpty()
                        if (allowEmpty || text.isNotBlank()) onConfirm(text.trim())
                    },
                )
            )
            vc.presentViewController(alert, animated = true, completion = null)
        }
    }
}

/**
 * iOS 端书源校验调度 (对照 desktop `DesktopCheckSource` / 鸿蒙 `OhosCheckSource`)。
 *
 * iOS 无 Android Service, 并发编排 (逐 id 取源 → 限流并发 → 进度回调 → 完成回调) 与执行线程池
 * 口径走 commonMain [CheckSourceRunner], 进度既发事件 (通知 UI) 也进通知栏 (对照 app 端
 * CheckSourceService: 通知 id / 标题 / 取消动作与 app 端同一套, 两处文案的取舍见 [upNotification])。
 */
private object IosCheckSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var checkJob: Job? = null

    /** 最近一次进度参数 (对照 app 端 CheckSourceService 的 notificationMsg/originSize: 重进页时重发)。 */
    @Volatile
    private var lastProgress: Triple<String, Int, Int>? = null

    /** 按书源 URL 启动校验 (共享对话框确认后走这条)。 */
    fun startUrls(ids: List<String>) {
        if (checkJob?.isActive == true) {
            Toasters.get().toast("已有书源在校验,等完成后再试")
            return
        }
        if (ids.isEmpty()) return
        checkJob = scope.launch {
            CheckSourceRunner.run(
                ids = ids,
                onStart = { total -> upNotification("", 0, total) },
                onProgress = { name, finishCount, total ->
                    upNotification(name, finishCount, total)
                },
                onComplete = {
                    // 对照 CheckSourceService.onDestroy
                    lastProgress = null
                    Debug.finishChecking()
                    postEvent(EventBus.CHECK_SOURCE_DONE, 0)
                    IosNotificationService.cancelNotification(NotificationId.CheckSourceService)
                }
            )
        }
        checkJob?.invokeOnCompletion { error ->
            if (error != null) AppLog.put("校验书源出错\n${error.message}", error)
        }
    }

    /** 校验中重新进入书源管理页时重发进度 (对照 app 端 `CheckSourceService` 的 IntentAction.resume)。 */
    fun resume() {
        if (checkJob?.isActive != true) return
        val (name, count, total) = lastProgress ?: return
        upNotification(name, count, total)
    }

    fun stop() {
        checkJob?.cancel()
        checkJob = null
        lastProgress = null
        Debug.finishChecking()
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
        // 对照 app 端 CheckSourceService.onDestroy 的 notificationManager.cancel
        IosNotificationService.cancelNotification(NotificationId.CheckSourceService)
    }

    /**
     * 进度事件 + 进度通知 (对照 app 端 CheckSourceService.upNotification: 同一次进度两处使用)。
     *
     * 事件正文沿用各端既有格式 (与 desktop/鸿蒙 同一串, 共享路由的进度标签按它显示);
     * 通知正文取 app 端同 key 文案 `progress_show` (与 app 端通知正文逐字同源)。
     */
    private fun upNotification(name: String, count: Int, total: Int) {
        lastProgress = Triple(name, count, total)
        postEvent(EventBus.CHECK_SOURCE, progressText(name, count, total))
        IosNotificationService.notify(
            NotificationId.CheckSourceService,
            syncGetString("check_book_source"),
            syncGetString("progress_show", name, count, total).trim(),
        )
    }

    /**
     * 进度事件正文 (首帧无书源名, 去掉前导空格, 对照 app 端 `progress_show` 首帧 trim)。
     */
    private fun progressText(name: String, count: Int, total: Int): String =
        "$name $count/$total".trim()
}
