@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
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
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.file.exportFile
import io.legado.app.help.storage.effectiveBookExportDir
import io.legado.app.help.file.pickDirectory as pickDirectoryDocument
import io.legado.app.help.openURL
import io.legado.app.help.copyToClipboard as copyTextToClipboard
import io.legado.app.help.readFromClipboard
import io.legado.app.help.toast.Toasters
import io.legado.app.help.topMostViewController
import io.legado.app.help.upLoadToDirectLink
import io.legado.app.model.CheckSourceShared
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
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.File
import io.legado.app.utils.GSON
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.IosSecurityScopeLease
import io.legado.app.utils.IosSecurityScopedStorage
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.toJson
import io.legado.app.utils.textCharsetCodec
import io.legado.app.web.WebServerManager
import kotlin.concurrent.Volatile
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var exportBooksJob: Job? = null

    /** 导出进度通知正文 (对照 app 端 ExportBookService.notificationContentText)。 */
    private var notificationContentText = syncGetString("service_starting")

    override val capabilityScope: CoroutineScope get() = scope

    override fun onHttpTtsEdited(engine: HttpTTS) {
        if (IosReadAloudHost.isRun && defaultTtsEngineConfig() == engine.id.toString()) {
            // 正在播放则重载；暂停时记录重建位置，继续朗读才启用新配置。
            IosReadAloudHost.play(play = !IosReadAloudHost.isPause)
        }
    }

    // iOS 无物理/手势返回键, PlatformBackHandler 是 no-op → 无系统返回通道,
    // 页面内的子状态需自带可见退出口。
    override val supportsSystemBack: Boolean get() = false

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
     * 导出分发「上传 URL」: 走 nativeMain 下沉的 [upLoadToDirectLink] (对照 app 端
     * `DirectLinkUpload.upLoad`)。失败路径也必须回调 (传 null) —— 导出对话框的防重复
     * 点击标志靠这一次回调解开, 不回调就是整个对话框永久点不动。
     */
    override fun upLoadFile(
        fileName: String,
        file: Any,
        contentType: String,
        onResult: (String?) -> Unit,
    ) {
        Coroutine.async { upLoadToDirectLink(fileName, file, contentType) }
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
    override fun initImportBookData() {
        val restored = IosSecurityScopedStorage.restoreImportRootPath()
        NativeImportBook.init(restoreLast = false)
        restored?.let { NativeImportBook.setRoot(it) }
    }

    override fun importBookItems(): StateFlow<List<ImportFileItem>> = NativeImportBook.items
    override fun importBookPath(): StateFlow<String?> = NativeImportBook.path
    override fun importBookLoading(): StateFlow<Boolean> = NativeImportBook.loading
    override fun importBookEmptyMsgVisible(): StateFlow<Boolean> =
        NativeImportBook.emptyMsgVisible

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
     * 选中后存 minimal bookmark 集合 (可跨启动), 回调仍传目录路径 (与其余端同一契约)。
     */
    override fun pickBookTreeUri(onSelected: (String?) -> Unit) {
        scope.launch {
            val url = pickDirectoryDocument()
            if (url == null) {
                onSelected(null)
                return@launch
            }
            val path = IosSecurityScopedStorage.addBookTree(url)
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

    // 对照 ChapterProvider.upLayout 的 appCtx.isPad ("平板/横屏双页" auto 分支);
    // iOS 用 UIDevice.userInterfaceIdiom 判定 (iPad 专用/通用应用在 iPad 上为 Pad)
    override fun isTablet(): Boolean =
        UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad

    // ===== Web 服务 (WebServerManager + NativeWebServerPlatform 已在 registerIosProviders 注册) =====

    // ===== 关于页 =====

    // ===== 书籍详情页 =====

    // 本地书文件字节数: 外部目录下的书籍文件走 security-scoped 授权读取属性
    // (bookUrl 形如 file:///path; 沙盒内文件无需授权)
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(Dispatchers.IO) {
        val path = bookUrl.removePrefix("file://")
        runCatching { IosSecurityScopedStorage.withAccess(path) { File(it).length() } }
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
            // 与 NativeFileBookAccessor 的有效书籍保存目录同源
            // (用户已选 → 选定目录; 未选 → 平台默认 {filesDir}/books);
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

    // 导出书籍正文, 直接写进有效导出目录 (用户选过 → 偏好键, 否则惯例目录
    // Documents/export —— iOS 的 Documents 经"文件"App 可见, 用户自己就能找到),
    // 不经系统分享面板 (对照 app 端 ExportBookService 的落盘语义)
    override fun exportAllBooks(books: List<Book>) {
        if (books.isEmpty()) {
            Toasters.get().toast("书籍不能为空")
            return
        }
        // 三分支同上, 未选过且无惯例目录时先选目录 (对照 BookshelfManageActivity.exportAll)
        if (effectiveBookExportDir() == null) {
            selectExportFolder(books)
            return
        }
        if (exportBooksJob?.isActive == true) {
            Toasters.get().toast("书籍正在导出")
            return
        }
        exportBooksJob = scope.launch {
            val (exportDir, lease) = openExportDir() ?: return@launch
            val failures = mutableListOf<String>()
            val usedPaths = mutableSetOf<String>()
            val exportType = prefs.getInt(PreferKey.exportType, 0)
            var exported = 0
            try {
                books.forEachIndexed { index, book ->
                    try {
                        currentCoroutineContext().ensureActive()
                        notificationContentText = syncGetString(
                            "export_book_notification_content",
                            book.name,
                            books.size - index - 1,
                        )
                        upExportNotification()
                        val file = NativeBookExporter.exportBookFile(book, exportDir, usedPaths, exportType)
                        exported++
                        if (prefs.getBoolean(PreferKey.exportToWebDav, false)) {
                            AppWebDavShared.exportWebDav(file.absolutePath, file.name)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures.add("${book.name}：${e.message ?: "未知错误"}")
                        AppLog.put("导出书籍失败：${book.name}\n${e.message}", e)
                    }
                }
            } finally {
                lease.close()
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
    }

    /**
     * 选择导出文件夹 (对照 BookshelfManageActivity.selectExportFolder 与 exportDir 回调):
     * 选定目录存 security-scoped bookmark 并记偏好键 [PreferKey.bookExportPath];
     * 开了自定义导出 (且导出类型为 epub) 先弹章节配置, 否则立即导出。
     *
     * 与 [pickBookTreeUri] 同一入口 ([pickDirectoryDocument]) + 同一授权表
     * ([IosSecurityScopedStorage]) —— 都不经只回路径的 `FilePickerService.pickDirectory`,
     * 否则下次访问拿不回授权。
     */
    override fun selectExportFolder(books: List<Book>) {
        scope.launch {
            val url = pickDirectoryDocument() ?: return@launch
            val path = IosSecurityScopedStorage.addBookTree(url) ?: run {
                Toasters.get().toast("无法保存导出目录的访问授权")
                return@launch
            }
            prefs.putString(PreferKey.bookExportPath, path)
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
     * (协调器会对尚不存在的目标路径报错); 沙盒内的惯例目录无需 scope, 租约为空操作。
     *
     * 授权已失效 (用户在系统里撤销/换过目录) 时清掉偏好键, 下次导出重新让用户选目录
     * (与图片保存目录写入失败即忘掉记忆目录同一处理)。
     *
     * @return 目录与租约 (调用方必须在 finally 里 [IosSecurityScopeLease.close]);
     *   已提示并返回 null 表示本次不能导出
     */
    private fun openExportDir(): Pair<File, IosSecurityScopeLease>? {
        val path = effectiveBookExportDir() ?: return null
        val lease = try {
            IosSecurityScopedStorage.acquireLease(path)
        } catch (e: Exception) {
            prefs.remove(PreferKey.bookExportPath)
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

    private fun exportCustomBooks(books: List<Book>, scope: Set<Int>, size: Int) {
        if (exportBooksJob?.isActive == true) {
            Toasters.get().toast("书籍正在导出")
            return
        }
        exportBooksJob = this.scope.launch {
            // 与 exportAllBooks 同一份导出目录判定与授权租约 (用户偏好键 → 平台惯例目录)
            val (exportDir, lease) = openExportDir() ?: return@launch
            val failures = mutableListOf<String>()
            val usedPaths = mutableSetOf<String>()
            var exported = 0
            try {
                books.forEachIndexed { index, book ->
                    try {
                        currentCoroutineContext().ensureActive()
                        notificationContentText = syncGetString(
                            "export_book_notification_content",
                            book.name,
                            books.size - index - 1,
                        )
                        upExportNotification()
                        val bookFiles = if (book.isImage) {
                            listOf(NativeBookExporter.exportBookFile(book, exportDir, usedPaths, 1))
                        } else {
                            val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                            val selected = chapters.filterIndexed { index, _ -> index in scope }
                            if (selected.isEmpty()) error("章节范围内没有章节")
                            selected.chunked(size).mapIndexed { index, part ->
                                NativeBookExporter.exportBookFile(
                                    book, exportDir, usedPaths, 1, part,
                                    book.getExportFileName("epub", index + 1),
                                )
                            }
                        }
                        bookFiles.forEach { file ->
                            exported++
                            if (prefs.getBoolean(PreferKey.exportToWebDav, false)) {
                                AppWebDavShared.exportWebDav(file.absolutePath, file.name)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures.add("${book.name}：${e.message ?: "未知错误"}")
                        AppLog.put("导出书籍失败：${book.name}\n${e.message}", e)
                    }
                }
            } finally {
                lease.close()
            }
            notificationContentText = "导出完成"
            upExportNotification(finish = true)
            if (exported > 0) {
                Toasters.get().toast("导出完成\n可在「文件」App 中查看")
            }
            if (failures.isNotEmpty()) Toasters.get().toast("${failures.size} 本书导出失败：${failures.take(2).joinToString()}")
        }
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
 * iOS 无 Android Service, 用协程 + [onEachParallel] 限流并发跑 [CheckSourceShared.checkSource]
 * (业务全流程已下沉), 进度既发事件 (通知 UI) 也进通知栏 (对照 app 端 CheckSourceService:
 * 通知 id / 标题 / 取消动作与 app 端同一套, 两处文案的取舍见 [upNotification])。
 */
private object IosCheckSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var checkJob: Job? = null

    /** 最近一次进度参数 (对照 app 端 CheckSourceService 的 notificationMsg/originSize: 重进页时重发)。 */
    @Volatile
    private var lastProgress: Triple<String, Int, Int>? = null

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
            // 初始帧 (对照 app 端 CheckSourceService.check 的 onStart 分支: 尚无书源名)
            upNotification("", 0, ids.size)
            flow<BookSource> {
                for (origin in ids) {
                    appDb.bookSourceDao.getBookSource(origin)?.let { emit(it) }
                }
            }.onEachParallel(AppConfigProviders.get().threadCount) {
                CheckSourceShared.checkSource(it)
            }.onEach { source ->
                finishCount++
                upNotification(source.bookSourceName, finishCount, ids.size)
                appDb.bookSourceDao.update(source)
            }.onCompletion {
                // 对照 CheckSourceService.onDestroy
                lastProgress = null
                Debug.finishChecking()
                postEvent(EventBus.CHECK_SOURCE_DONE, 0)
                IosNotificationService.cancelNotification(NotificationId.CheckSourceService)
            }.collect { }
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
