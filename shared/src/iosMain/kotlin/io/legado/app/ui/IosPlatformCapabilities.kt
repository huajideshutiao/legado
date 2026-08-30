@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.book.toShelfJsonMap
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.exportFile
import io.legado.app.help.file.pickDirectory as pickDirectoryDocument
import io.legado.app.help.openURL
import io.legado.app.help.copyToClipboard as copyTextToClipboard
import io.legado.app.help.readFromClipboard
import io.legado.app.help.toast.Toasters
import io.legado.app.help.topMostViewController
import io.legado.app.model.CheckSourceShared
import io.legado.app.model.Debug
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.book.source.manage.BookSourceViewModelShared
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.DefaultDialogTransitionSpec
import io.legado.app.ui.root.DialogTransitionSpec
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteTransitionSpec
import io.legado.app.ui.root.TransitionEasing
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.File
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val capabilityScope: CoroutineScope get() = scope

    private val appDb get() = AppDbProviders.get()
    private val prefs get() = PreferenceProviders.get()
    private val services get() = PlatformServiceProviders.getOrNull()

    /** 书源分组增删改 (shared 下沉件)。 */
    private val bookSourceViewModel by lazy { BookSourceViewModelShared(scope) }

    /**
     * 导入根目录的 security-scoped URL (UIDocumentPicker Open 模式选出的目录)。
     * 持有 NSURL 引用 + 保持 startAccessing 才能访问目录内容; 换目录时释放旧 scope。
     */
    private var importRootUrl: NSURL? = null

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

    override fun shareText(text: String) {
        presentShareSheet(listOf(text))
    }

    override fun copyToClipboard(text: String) {
        copyTextToClipboard(text)
    }

    // 读系统剪贴板 (对照原版 ContextExtensions getClipText: 主题导入/规则粘贴等 7 场景)
    override fun getClipboardText(): String? = readFromClipboard()

    // 完整分发链 (压缩包/JSON 一键导入/书籍文件) 见 NativeFileAssociationDispatch, 与鸿蒙共用
    override fun openImportFile(filePath: String) {
        scope.launch { NativeFileAssociationDispatch.dispatch(filePath) }
    }

    // ===== 导入本地书 (状态与扫描见 NativeImportBook, 与鸿蒙共用) =====

    override fun initImportBookData() = NativeImportBook.init(restoreLast = false)

    override fun importBookItems(): StateFlow<List<ImportFileItem>> = NativeImportBook.items
    override fun importBookPath(): StateFlow<String?> = NativeImportBook.path
    override fun importBookLoading(): StateFlow<Boolean> = NativeImportBook.loading
    override fun importBookEmptyMsgVisible(): StateFlow<Boolean> =
        NativeImportBook.emptyMsgVisible

    // 对照 Android onPickFolder / selectFolder.launch。
    // 不用 IosFilePickerService.pickDirectory (只返回 path, 会丢 security-scoped URL):
    // 这里直接拿 NSURL 并 startAccessingSecurityScopedResource, 否则读取授权目录内容会失败
    // (iOS Open 模式选出的目录必须持有 scope 才能访问, 权限随应用会话有效)。
    override fun pickImportFolder() {
        scope.launch {
            val url = pickDirectoryDocument() ?: return@launch
            val path = url.path ?: return@launch
            // 换目录时释放上一目录的 scope, 保持有界
            importRootUrl?.stopAccessingSecurityScopedResource()
            importRootUrl = url
            // 返回 false = 无需 scope (如应用沙盒内目录), 忽略即可
            url.startAccessingSecurityScopedResource()
            NativeImportBook.setRoot(path)
        }
    }

    override fun scanImportFolder() = NativeImportBook.scan()

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

    override fun addImportSelectionToBookshelf(
        items: List<ImportFileItem>,
        onComplete: () -> Unit,
    ) = NativeImportBook.addToBookshelf(items, onComplete)

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

    // ===== Web 服务 (WebServerManager + NativeWebServerPlatform 已在 registerIosProviders 注册) =====

    // ===== 关于页 =====

    // ===== 书籍详情页 =====

    // 本地书文件字节数 (bookUrl 形如 file:///path, iOS 沙盒为 POSIX 路径, 去 scheme 即可)
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(Dispatchers.IO) {
        runCatching { File(bookUrl.removePrefix("file://")).length() }.getOrDefault(0L)
    }

    // ===== 书架管理: 导出开关 =====

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
