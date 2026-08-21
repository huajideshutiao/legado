@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.Review
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.toShelfJsonMap
import io.legado.app.help.book.toggleBookshelfCore
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.exportFile
import io.legado.app.help.openURL
import io.legado.app.help.copyToClipboard as copyTextToClipboard
import io.legado.app.help.toast.Toasters
import io.legado.app.help.topMostViewController
import io.legado.app.model.CheckSourceShared
import io.legado.app.model.Debug
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.book.source.manage.BookSourceViewModelShared
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.DefaultDialogTransitionSpec
import io.legado.app.ui.root.DialogTransitionSpec
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteTransitionSpec
import io.legado.app.ui.root.TransitionEasing
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.route.encodeReviewListDialogPayload
import io.legado.app.ui.root.encodeBookVariableOverlayPayload
import io.legado.app.ui.root.encodeSourceVariableOverlayPayload
import io.legado.app.utils.File
import io.legado.app.utils.FlowBus
import io.legado.app.utils.GSON
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.toJson
import io.legado.app.web.WebServerManager
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.setAlternateIconName
import platform.UIKit.UIScreen
import platform.UIKit.UITextField
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 端 [PlatformCapabilities]: 内核已下沉的能力直接复用 shared 实现 (对照 desktop / 鸿蒙),
 * 需系统面板的能力走 UIKit (导出 UIDocumentPicker / 文本输入 UIAlertController)。
 * 依赖命令式对话框宿主 (主题列表/分组管理/书籍变量/导入书籍浏览) 的能力保持 unsupported。
 */
object IosPlatformCapabilities : PlatformCapabilities {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appDb get() = AppDbProviders.get()
    private val prefs get() = PreferenceProviders.get()
    private val services get() = PlatformServiceProviders.getOrNull()

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

    override fun openWebView(url: String, sourceKey: String, sourceName: String) {
        // 移动端保留内嵌 WebViewRoute 路由语义 (对话框内嵌)
        AppNavigatorProviders.getOrNull()?.push(AppRoute.WebView(url, sourceKey, sourceName))
    }

    override fun shareText(text: String) {
        presentShareSheet(listOf(text))
    }

    override fun copyToClipboard(text: String) {
        copyTextToClipboard(text)
    }

    // 完整分发链 (压缩包/JSON 一键导入/书籍文件) 见 NativeFileAssociationDispatch, 与鸿蒙共用
    override fun openImportFile(filePath: String) {
        scope.launch { NativeFileAssociationDispatch.dispatch(filePath) }
    }

    // 按 bookUrl 查 DB 解析 BookRef, 供 deep link / 文件关联的路由导航
    override suspend fun resolveBookRef(bookUrl: String): BookRef? =
        appDb.bookDao.getBook(bookUrl)?.toRouteRef()

    // AppTheme 直接订阅 ThemeStore/AppConfig, 日夜切换即重组, 无 Activity.recreate 需求
    override fun applyDayNight() = Unit

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

    // iOS 无 scaledTouchSlop, 取 UIPanGestureRecognizer 默认判定距离 10pt (仅用于设置页摘要展示)
    override fun getScaledTouchSlop(): Int = 10

    // ===== Web 服务 (WebServerManager + NativeWebServerPlatform 已在 registerIosProviders 注册) =====

    override fun getWebServiceUrl(): String? =
        WebServerManager.hostAddress.takeIf { it.isNotEmpty() }

    override fun isWebServiceRunning(): Boolean = WebServerManager.isRun

    override fun setWebService(enabled: Boolean) {
        scope.launch {
            runCatching { if (enabled) WebServerManager.start() else WebServerManager.stop() }
                .onFailure { AppLog.put("Web 服务启停失败\n${it.message}", it) }
        }
    }

    // 对照 app 端 FlowBus.withSticky(EventBus.WEB_SERVICE) 桥接到 StateFlow
    private val webServiceRunningState: MutableStateFlow<Boolean> by lazy {
        MutableStateFlow(WebServerManager.isRun).also { state ->
            scope.launch {
                FlowBus.with(EventBus.WEB_SERVICE).collect { state.value = WebServerManager.isRun }
            }
        }
    }

    override val webServiceState: StateFlow<Boolean>? get() = webServiceRunningState

    // ===== 关于页 =====

    // iOS 无 assets, 直接打开仓库上的文档 (对照 desktop/鸿蒙 showMdFile)
    override fun showMdFile(title: String, fileName: String) {
        val path = if (fileName == "LICENSE.md") "LICENSE" else "app/src/main/assets/$fileName"
        runCatching { openExternalUrl("https://github.com/gedoor/legado/blob/master/$path") }
    }

    // ===== 书籍详情页 =====

    override fun clearBookCache(book: Book) {
        scope.launch {
            runCatching { BookStorageProviders.get().clearCache(book) }
                .onSuccess { Toasters.get().toast("清理缓存成功") }
                .onFailure { Toasters.get().toast("清理缓存出错\n${it.message}") }
        }
    }

    // 书源变量: 对照原版 BaseSource.showSourceVariableDialog, 经 AppOverlay 弹 shared
    // SourceVariableDialog (编辑 source.getVariable() 的原始 JSON 文本, 确定后 setVariable
    // 原样写回, 不解析不校验); 渲染/保存逻辑见 VariableOverlayDialog.kt
    override fun showSourceVariableDialog(book: Book) {
        scope.launch {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            if (source == null) {
                Toasters.get().toast("未找到书源")
                return@launch
            }
            showBookSourceVariableDialog(source)
        }
    }

    override fun showBookSourceVariableDialog(source: BookSource) {
        AppNavigatorProviders.getOrNull()?.showOverlay(
            AppOverlay.Dialog(
                key = "sourceVariable",
                payload = encodeSourceVariableOverlayPayload(source),
            )
        )
    }

    // 书籍变量: 对照原版 BaseBook.showBookVariableDialog, 只编辑 book.variable 的 "custom" 键
    // (getCustomVariable/putCustomVariable 保留其他键), 经 AppOverlay 弹 shared
    // BookVariableDialog, 确定后写库持久化 (逻辑见 VariableOverlayDialog.kt);
    // 推 Overlay 前先查源: 原版拿不到 BookSource 直接 return (不弹窗)
    override fun showBookVariableDialog(book: Book) {
        scope.launch {
            val source = appDb.bookSourceDao.getBookSource(book.origin) ?: return@launch
            AppNavigatorProviders.getOrNull()?.showOverlay(
                AppOverlay.Dialog(
                    key = "bookVariable",
                    payload = encodeBookVariableOverlayPayload(book, source),
                )
            )
        }
    }

    override fun evalIntroAction(book: Book, js: String) {
        val action = js.trim().ifEmpty { return }
        scope.launch {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            if (source == null) {
                Toasters.get().toast("未找到书源")
                return@launch
            }
            runCatching { source.evalJS(action) { this["book"] = book } }
                .onFailure { Toasters.get().toast(it.message ?: it::class.simpleName.orEmpty()) }
        }
    }

    // 上架/下架 (DB 逻辑统一走 shared toggleBookshelfCore, 与桌面/鸿蒙/Android 一致;
    // 无删除确认弹窗, 无平台专属前后处理)
    override fun toggleBookshelf(
        book: Book,
        inBookshelf: Boolean,
        onComplete: (Boolean?) -> Unit,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
    ) {
        scope.launch {
            runCatching { book.toggleBookshelfCore(inBookshelf) }
                .onSuccess { onComplete(it) }
                .onFailure {
                    AppLog.put("书架操作失败\n${it.message}", it)
                    onComplete(false)
                }
        }
    }

    // 本地书文件字节数 (bookUrl 形如 file:///path, iOS 沙盒为 POSIX 路径, 去 scheme 即可)
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(Dispatchers.IO) {
        runCatching { File(bookUrl.removePrefix("file://")).length() }.getOrDefault(0L)
    }

    // ===== 书架管理: 导出开关 =====

    override fun exportUseReplace(): Boolean = prefs.getBoolean(PreferKey.exportUseReplace, true)

    override fun enableCustomExport(): Boolean =
        prefs.getBoolean(PreferKey.enableCustomExport, false)

    override fun exportToWebDav(): Boolean = prefs.getBoolean(PreferKey.exportToWebDav, false)

    override fun toggleExportUseReplace() {
        prefs.putBoolean(PreferKey.exportUseReplace, !exportUseReplace())
    }

    override fun toggleCustomExport() {
        prefs.putBoolean(PreferKey.enableCustomExport, !enableCustomExport())
    }

    override fun toggleExportWebDav() {
        prefs.putBoolean(PreferKey.exportToWebDav, !exportToWebDav())
    }

    override fun getDeleteBookOriginal(): Boolean =
        prefs.getBoolean(LocalConfigKeys.deleteBookOriginal, false)

    override fun setDeleteBookOriginal(value: Boolean) {
        prefs.putBoolean(LocalConfigKeys.deleteBookOriginal, value)
    }

    // ===== 书架管理: 导出 JSON =====

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

    override fun addBookSource() {
        AppNavigatorProviders.getOrNull()?.push(AppRoute.BookSourceEdit(""))
    }

    override fun exportBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean,
        sort: BookSourceSort
    ) {
        scope.launch {
            val json = selectedSourcesJson(selection) ?: return@launch
            exportJson("bookSource.json", json)
        }
    }

    override fun shareBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean,
        sort: BookSourceSort
    ) {
        scope.launch {
            val json = selectedSourcesJson(selection) ?: return@launch
            shareText(json)
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

    override fun checkBookSource(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        IosCheckSource.start(selection)
    }

    override fun cancelCheckSource() {
        IosCheckSource.stop()
        Debug.finishChecking()
    }

    // ===== 其它设置 =====

    override fun setLocalPassword(password: String?) {
        prefs.putString(LocalConfigKeys.password, password)
    }

    // 段评/书评列表: shared 底部弹窗 Overlay (对照 app 端 ReviewListDialog BottomSheetDialogFragment;
    // 回复详情再开一层由宿主内部处理, 见 ReviewListDialogHost.kt)
    override fun showReviewListDialog(
        book: Book,
        chapter: BookChapter?,
        paragraphIndex: Int,
        parentReview: Review?,
    ): Boolean {
        AppNavigatorProviders.getOrNull()?.showOverlay(
            AppOverlay.Dialog(
                key = "review_list",
                payload = encodeReviewListDialogPayload(
                    book,
                    chapter,
                    paragraphIndex,
                    parentReview
                ),
            )
        )
        return true
    }

    // 默认封面图集: shared 管理对话框 Overlay (对照 app 端 DefaultCoverGalleryDialog;
    // 选图加入/删除走 shared 逻辑, 见 DefaultCoverGalleryHost.kt)
    override fun showDefaultCoverGallery(isNight: Boolean) {
        AppNavigatorProviders.getOrNull()?.showOverlay(
            AppOverlay.Dialog(
                key = "default_cover_gallery",
                payload = if (isNight) "1" else "0",
            )
        )
    }

    // 刷新默认封面缓存: shared 默认封面链每次组合重读 prefs, 无内存缓存,
    // 广播书架刷新让封面槽重组重读即可 (对照 app 端 BookCover.upDefaultCover)
    override fun refreshDefaultCover() {
        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
    }

    override fun pickBookTreeUri(onSelected: (String?) -> Unit) {
        scope.launch { onSelected(services?.files?.pickDirectory()) }
    }

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

    /** 选中书源转 JSON (对照 desktop selectedSourcesJson: 导出前强制关闭危险 API 开关)。 */
    private suspend fun selectedSourcesJson(selection: List<BookSourcePart>): String? {
        val urls = selection.map { it.bookSourceUrl }
        val sources = runCatching { appDb.bookSourceDao.getBookSourcesFix(urls) }.getOrElse {
            Toasters.get().toast("导出书源失败\n${it.message}")
            return null
        }
        sources.forEach { if (it.enableDangerousApi == true) it.enableDangerousApi = false }
        return GSON.toJson(sources)
    }

    /** 经 [exportFile] 弹系统"文件"面板导出 (用户可存到本机/iCloud/第三方网盘)。 */
    private suspend fun exportJson(defaultName: String, json: String) {
        val saved = runCatching { exportFile(defaultName, json.encodeToByteArray()) }
            .getOrElse {
                Toasters.get().toast("导出失败\n${it.message}")
                return
            }
        if (saved) Toasters.get().toast("已导出 $defaultName")
    }

    /** 单行文本输入弹窗 (对照 desktop DesktopDialogRequest.TextInput), 空输入不回调。 */
    private fun presentTextInput(
        title: String,
        hint: String,
        initial: String = "",
        onConfirm: (String) -> Unit,
    ) {
        dispatch_async(dispatch_get_main_queue()) {
            val vc = topMostViewController() ?: return@dispatch_async
            val alert = UIAlertController.alertControllerWithTitle(
                title = title,
                message = null,
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
                        if (text.isNotBlank()) onConfirm(text.trim())
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
 * iOS 无 Android Service, 用协程 + [onEachParallel] 限流并发跑 [CheckSourceShared.checkSource]
 * (业务全流程已下沉), 进度经 EventBus 通知 UI。
 */
private object IosCheckSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var checkJob: Job? = null

    fun start(selection: List<BookSourcePart>) {
        if (checkJob?.isActive == true) {
            Toasters.get().toast("已有书源在校验,等完成后再试")
            return
        }
        val ids = selection.map { it.bookSourceUrl }
        if (ids.isEmpty()) return
        val appDb = AppDbProviders.get()
        var finishCount = 0
        checkJob = scope.launch {
            flow<BookSource> {
                for (origin in ids) {
                    appDb.bookSourceDao.getBookSource(origin)?.let { emit(it) }
                }
            }.onEachParallel(AppConfigProviders.get().threadCount) {
                CheckSourceShared.checkSource(it)
            }.onEach { source ->
                finishCount++
                postEvent(EventBus.CHECK_SOURCE, "${source.bookSourceName} $finishCount/${ids.size}")
                appDb.bookSourceDao.update(source)
            }.onCompletion {
                // 对照 CheckSourceService.onDestroy
                Debug.finishChecking()
                postEvent(EventBus.CHECK_SOURCE_DONE, 0)
            }.collect { }
        }
        checkJob?.invokeOnCompletion { error ->
            if (error != null) AppLog.put("校验书源出错\n${error.message}", error)
        }
    }

    fun stop() {
        checkJob?.cancel()
        checkJob = null
        Debug.finishChecking()
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
    }
}
