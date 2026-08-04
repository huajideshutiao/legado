package io.legado.desktop.ui

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.Review
import io.legado.app.help.DirectLinkUploadRule
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.isImage
import io.legado.app.help.book.toggleBookshelfCore
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.Debug
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.source.manage.BookSourceViewModelShared
import io.legado.app.ui.config.MODE_EDIT_CONFIG
import io.legado.app.ui.config.MODE_EDIT_PREFS
import io.legado.app.ui.config.MODE_NEW_CONFIG
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.route.encodeReviewListDialogPayload
import io.legado.app.utils.FlowBus
import io.legado.app.utils.GSON
import io.legado.app.utils.RemoteAssetsUtils
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap
import io.legado.app.utils.toJson
import io.legado.app.web.WebServerManager
import io.legado.desktop.constant.DesktopAppInfo
import io.legado.desktop.help.book.DesktopBookExport
import io.legado.desktop.help.source.DesktopCheckSource
import io.legado.desktop.model.fileBook.DesktopImportBook
import io.legado.desktop.model.fileBook.DesktopImportFile
import io.legado.desktop.ui.component.FileDialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

object DesktopPlatformCapabilities : PlatformCapabilities {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appDb get() = AppDbProviders.get()
    private val prefs get() = PreferenceProviders.get()

    /** 书源分组增删改 (shared 下沉件), 供 [DesktopDialogHost] 的分组管理对话框调用。 */
    internal val bookSourceViewModel by lazy { BookSourceViewModelShared(scope) }

    override fun exitApplication() = Unit

    override fun openExternalUrl(url: String) {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    }

    override fun shareText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun copyToClipboard(text: String) {
        shareText(text)
    }

    override fun getClipboardText(): String? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null
        clipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun testDirectLinkUpload(
        rule: DirectLinkUploadRule,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            runCatching {
                io.legado.desktop.help.DesktopDirectLinkUpload.upLoad(
                    "test.json", "{}", "application/json", rule,
                )
            }.onSuccess { onSuccess(it) }
                .onFailure { onError(it.localizedMessage ?: it.toString()) }
        }
    }

    // 按 bookUrl 查 DB 解析 BookRef, 供 deep link / 文件关联的路由导航
    override suspend fun resolveBookRef(bookUrl: String): BookRef? =
        appDb.bookDao.getBook(bookUrl)?.toRouteRef()

    // 桌面端 AppTheme 直接订阅 ThemeStore/AppConfig, 日夜切换即重组, 无 Activity.recreate 需求
    override fun applyDayNight() = Unit

    override fun showThemeListDialog() {
        AppNavigatorProviders.getOrNull()?.showOverlay(AppOverlay.Dialog("theme_list"))
    }

    // 书架布局 / 底栏配置: shared Compose 对话框 (BookshelfNavConfigDialogs.kt)
    override fun showBookshelfLayoutDialog() {
        AppNavigatorProviders.getOrNull()?.showOverlay(AppOverlay.Dialog("bookshelf_layout"))
    }

    override fun showBottomNavConfigDialog() {
        AppNavigatorProviders.getOrNull()?.showOverlay(AppOverlay.Dialog("bottom_nav_config"))
    }

    override fun showThemeCustomizeDialog(configIndex: Int?, isNight: Boolean) {
        val mode = if (configIndex == null) MODE_NEW_CONFIG else MODE_EDIT_CONFIG
        val index = configIndex ?: -1
        AppNavigatorProviders.getOrNull()
            ?.showOverlay(AppOverlay.Dialog("theme_customize", payload = "$mode,$index,$isNight"))
    }

    override fun showCustomizeDayThemeDialog() {
        AppNavigatorProviders.getOrNull()
            ?.showOverlay(AppOverlay.Dialog("theme_customize", payload = "$MODE_EDIT_PREFS,-1,false"))
    }

    override fun showCustomizeNightThemeDialog() {
        AppNavigatorProviders.getOrNull()
            ?.showOverlay(AppOverlay.Dialog("theme_customize", payload = "$MODE_EDIT_PREFS,-1,true"))
    }

    override fun getAppVersionName(): String? = DesktopAppInfo.versionName

    // 桌面窗口无系统状态栏/导航栏, 也无屏幕方向: 对应设置项隐藏
    override fun hasSystemBars(): Boolean = false

    override fun hasScreenOrientation(): Boolean = false

    // ===== Web 服务 (WebServerManager 已下沉) =====

    override fun getWebServiceUrl(): String? = WebServerManager.hostAddress.takeIf { it.isNotEmpty() }

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
    // 检查更新不再走这里: shared AboutRoute 直接调 AboutScreenModel.checkUpdate →
    // AppUpdateManager (环境/执行器由 registerDesktopAppUpdate 注册, 见 DesktopAppUpdate.kt)

    // 桌面端无 assets, 直接打开仓库上的文档 (对照 app 端 showMdFile 读不到资源时的回退分支)
    override fun showMdFile(title: String, fileName: String) {
        val path = if (fileName == "LICENSE.md") "LICENSE" else "app/src/main/assets/$fileName"
        runCatching { openExternalUrl("https://github.com/huajideshutiao/legado/blob/master/$path") }
    }

    // ===== 书籍详情页 =====

    override fun clearBookCache(book: Book) {
        scope.launch {
            runCatching { BookStorageProviders.get().clearCache(book) }
                .onSuccess { Toasters.get().toast("清理缓存成功") }
                .onFailure { Toasters.get().toast("清理缓存出错\n${it.message}") }
        }
    }

    // 书源变量: 经 FlowBus 走 shared SourceUiEventBridgeHost (Main.kt 已挂载)
    override fun showSourceVariableDialog(book: Book) {
        scope.launch {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            if (source == null) {
                Toasters.get().toast("未找到书源")
                return@launch
            }
            source.showSourceVariableDialog()
        }
    }

    override fun showBookSourceVariableDialog(source: BookSource) {
        source.showSourceVariableDialog()
    }

    override fun showBookSourceLogin(source: BookSource) {
        source.showLoginDialog()
    }

    // 书籍变量: shared VariableDialog 是 Map 批量编辑器, 经桌面对话框宿主弹出
    override fun showBookVariableDialog(book: Book) {
        scope.launch {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            val bookVars = decodeStringMapOrNull(book.variable) ?: emptyMap()
            val sourceVars = source?.let { decodeStringMapOrNull(it.getVariable()) } ?: emptyMap()
            DesktopDialogs.show(
                DesktopDialogRequest.Variable(sourceVars, bookVars) { newSourceVars, newBookVars ->
                    scope.launch {
                        source?.setVariable(encodeStringMap(newSourceVars))
                        // 写库前重查, 避免用入队时的旧快照整行覆盖用户并发修改
                        appDb.bookDao.getBook(book.bookUrl)?.let { latest ->
                            latest.variable = encodeStringMap(newBookVars)
                            appDb.bookDao.update(latest)
                        }
                    }
                }
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

    // 上架/下架 (DB 逻辑统一走 shared toggleBookshelfCore, 与 iOS/鸿蒙/Android 一致;
    // 桌面端无删除确认弹窗, 无平台专属前后处理)
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

    // 本地书文件字节数 (对照 app 端 FileDoc.fromFile(bookUrl).size; bookUrl 形如 file:///path)
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(Dispatchers.IO) {
        runCatching { resolveLocalFile(bookUrl).length() }.getOrDefault(0L)
    }

    // ===== 书架管理: 导出 =====

    override fun exportUseReplace(): Boolean = prefs.getBoolean(PreferKey.exportUseReplace, true)
    override fun enableCustomExport(): Boolean = prefs.getBoolean(PreferKey.enableCustomExport, false)
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

    override fun getDeleteBookOriginal(): Boolean = prefs.getBoolean(LocalConfigKeys.deleteBookOriginal, false)

    override fun setDeleteBookOriginal(value: Boolean) {
        prefs.putBoolean(LocalConfigKeys.deleteBookOriginal, value)
    }

    // 导出书籍所用书源 JSON (对照 exportAllUseBookSource)
    override fun exportAllUseBookSource() {
        scope.launch {
            val sources = runCatching { appDb.bookDao.getAllUseBookSource() }.getOrElse {
                Toasters.get().toast("导出所用书源失败\n${it.message}")
                return@launch
            }
            saveJsonToPickedFile("bookSource.json", GSON.toJson(sources))
        }
    }

    // 导出书架 JSON (字段清单与 app 端 exportBookshelf 一致)
    override fun exportBookshelf(books: List<Book>) {
        if (books.isEmpty()) {
            Toasters.get().toast("书籍不能为空")
            return
        }
        scope.launch { saveJsonToPickedFile("bookshelf.json", GSON.toJson(books.map { it.toShelfJsonMap() })) }
    }

    // 导出书籍正文, 格式取导出配置 (0=txt 1=epub, 对照 app 端 AppConfig.exportType;
    // 图片书自动走 cbz), 目录来自 pref 或现选
    override fun exportAllBooks(books: List<Book>) {
        if (books.isEmpty()) return
        val cached = prefs.getString(KEY_EXPORT_BOOK_PATH, "").takeIf { File(it).isDirectory }
            ?: defaultBookExportDir()
        if (cached == null) {
            selectExportFolder(books)
            return
        }
        startExport(cached, books)
    }

    override fun selectExportFolder(books: List<Book>) {
        scope.launch {
            val dir = FileDialogs.pickDirectory(
                "选择导出目录",
                initialDir = defaultBookExportDir()?.let(::File),
            ) ?: return@launch
            prefs.putString(KEY_EXPORT_BOOK_PATH, dir.absolutePath)
            if (books.isNotEmpty()) {
                // 对照 app 端 exportDir 回调: 开启自定义导出时先弹章节配置对话框
                if (enableCustomExport()) {
                    showExportSectionConfig(dir.absolutePath, books)
                } else {
                    startExport(dir.absolutePath, books)
                }
            }
        }
    }

    /** 用户没选过导出目录时的默认落点 (桌面/legado/books), 创建失败返回 null 走目录选择器。 */
    private fun defaultBookExportDir(): String? = runCatching {
        val dir = File(DataStorageProviders.get().bookExportDir)
        if (dir.isDirectory || dir.mkdirs()) dir.absolutePath else null
    }.getOrNull()

    // 导出配置弹窗 (对照 app 端 showExportConfig / dialog_export_config.xml 全量字段):
    // 导出文件名 JS 规则 / 导出类型 txt|epub / 导出编码 / TXT 不导出章节名
    // (cbz 按 app 端语义由图片书自动选择, 无需用户配置)
    override fun showExportConfig() {
        DesktopDialogs.show(
            DesktopDialogRequest.ExportConfig(
                currentType = prefs.getInt(PreferKey.exportType, 0),
                currentFileName = prefs.getString(PreferKey.bookExportFileName, ""),
                currentCharset = prefs.getString(PreferKey.exportCharset, ""),
                currentNoChapterName = prefs.getBoolean(PreferKey.exportNoChapterName, false),
                onConfirm = { type, fileName, charset, noChapterName ->
                    prefs.putInt(PreferKey.exportType, type)
                    prefs.putString(PreferKey.bookExportFileName, fileName)
                    prefs.putString(PreferKey.exportCharset, charset)
                    prefs.putBoolean(PreferKey.exportNoChapterName, noChapterName)
                },
            )
        )
    }

    // 自定义导出章节配置弹窗 (对照 app 端 configExportSection / dialog_select_section_export.xml):
    // 导出所有 / 自定义导出 (章节范围 + epub 分卷大小 + epub 文件名 JS 规则)
    override fun showExportSectionConfig(path: String, books: List<Book>) {
        DesktopDialogs.show(
            DesktopDialogRequest.ExportSectionConfig(
                path = path,
                books = books,
                currentFileName = prefs.getString(PreferKey.episodeExportFileName, ""),
                onConfirm = { all, scope, size, fileName ->
                    // 分卷文件名 JS 规则仅在合法时持久化 (对照 etEpubFilename 失焦校验)
                    if (tryParesExportFileName(fileName)) {
                        prefs.putString(PreferKey.episodeExportFileName, fileName)
                    }
                    if (all) {
                        startExport(path, books)
                    } else {
                        startCustomExport(path, books, scope, size)
                    }
                },
            )
        )
    }

    private fun startCustomExport(path: String, books: List<Book>, range: String, size: Int) {
        if (books.isEmpty()) return
        scope.launch {
            Toasters.get().toast("开始导出 ${books.size} 本 (自定义章节)")
            try {
                DesktopBookExport.exportCustomEpub(path, books, range, size)
                Toasters.get().toast("导出完成\n$path")
            } catch (e: Exception) {
                AppLog.put("导出书籍出错\n${e.message}", e)
                Toasters.get().toast("导出书籍出错\n${e.message}")
            }
        }
    }

    private fun startExport(dir: String, books: List<Book>) {
        val type = prefs.getInt(PreferKey.exportType, 0)
        scope.launch {
            Toasters.get().toast("开始导出 ${books.size} 本")
            runCatching {
                // 对照 app 端 MainActivity.startExportBooks: 图片书一律导出 cbz,
                // 其余按配置类型 (0=txt 1=epub)
                val txtBooks = arrayListOf<Book>()
                val epubBooks = arrayListOf<Book>()
                val cbzBooks = arrayListOf<Book>()
                books.forEach { book ->
                    when {
                        book.isImage -> cbzBooks.add(book)
                        type == 1 -> epubBooks.add(book)
                        else -> txtBooks.add(book)
                    }
                }
                if (txtBooks.isNotEmpty()) DesktopBookExport.exportTxt(dir, txtBooks)
                if (epubBooks.isNotEmpty()) DesktopBookExport.exportEpub(dir, epubBooks)
                if (cbzBooks.isNotEmpty()) DesktopBookExport.exportCbz(dir, cbzBooks)
            }
                .onSuccess { Toasters.get().toast("导出完成\n$dir") }
                .onFailure {
                    AppLog.put("导出书籍出错\n${it.message}", it)
                    Toasters.get().toast("导出书籍出错\n${it.message}")
                }
        }
    }

    // ===== 书源管理 =====

    override fun addBookSource() {
        AppNavigatorProviders.getOrNull()?.push(AppRoute.BookSourceEdit(""))
    }

    override fun showBookSourceGroupManage() {
        DesktopDialogs.show(DesktopDialogRequest.BookSourceGroupManage)
    }

    override fun selectionAddToGroups(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        DesktopDialogs.show(
            DesktopDialogRequest.TextInput(title = "添加分组", hint = "分组名") { group ->
                val name = group.trim()
                if (name.isEmpty()) return@TextInput
                bookSourceViewModel.selectionAddToGroups(selection, name)
            }
        )
    }

    override fun selectionRemoveFromGroups(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        DesktopDialogs.show(
            DesktopDialogRequest.TextInput(title = "移除分组", hint = "分组名") { group ->
                val name = group.trim()
                if (name.isEmpty()) return@TextInput
                bookSourceViewModel.selectionRemoveFromGroups(selection, name)
            }
        )
    }

    override fun exportBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean
    ) {
        scope.launch {
            val json = selectedSourcesJson(selection) ?: return@launch
            saveJsonToPickedFile("bookSource.json", json)
        }
    }

    // 桌面端无系统分享面板, 与 shareText 一致落剪贴板 (对照 DesktopShareService.shareText)
    override fun shareBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean
    ) {
        scope.launch {
            val json = selectedSourcesJson(selection) ?: return@launch
            shareText(json)
            Toasters.get().toast("已复制到剪贴板")
        }
    }

    override fun checkBookSource(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        DesktopCheckSource.start(selection)
    }

    override fun cancelCheckSource() {
        DesktopCheckSource.stop()
        Debug.finishChecking()
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

    // ===== 其它设置 =====

    override fun setLocalPassword(password: String?) {
        prefs.putString(LocalConfigKeys.password, password)
    }

    override fun pickBookTreeUri(onSelected: (String?) -> Unit) {
        scope.launch { onSelected(FileDialogs.pickDirectory("选择书籍目录")?.absolutePath) }
    }

    // ===== 导入本地书 (状态与扫描见 DesktopImportBook) =====

    override fun initImportBookData() = DesktopImportBook.init()

    override fun importBookItems(): StateFlow<List<ImportFileItem>> = DesktopImportBook.items
    override fun importBookPath(): StateFlow<String?> = DesktopImportBook.path
    override fun importBookLoading(): StateFlow<Boolean> = DesktopImportBook.loading
    override fun importBookEmptyMsgVisible(): StateFlow<Boolean> = DesktopImportBook.emptyMsgVisible

    override fun pickImportFolder() {
        scope.launch { FileDialogs.pickDirectory("选择导入目录")?.let { DesktopImportBook.setRoot(it) } }
    }

    override fun scanImportFolder() = DesktopImportBook.scan()

    override fun alertImportFileName() {
        DesktopDialogs.show(
            DesktopDialogRequest.TextInput(
                title = "按文件名导入",
                message = "使用js处理文件名变量src，将书名作者分别赋值到变量name author",
                initialValue = prefs.getString(PreferKey.bookImportFileName, ""),
                hint = "js",
            ) { prefs.putString(PreferKey.bookImportFileName, it) }
        )
    }

    override fun addImportSelectionToBookshelf(items: List<ImportFileItem>, onComplete: () -> Unit) {
        DesktopImportBook.addToBookshelf(items, onComplete)
    }

    override fun updateImportBookFilter(key: String) = DesktopImportBook.updateFilter(key)

    override fun updateImportBookSort(sort: Int) = DesktopImportBook.updateSort(sort)

    override fun navigateImportDir(item: ImportFileItem) = DesktopImportBook.enterDir(item)

    override fun goBackImportDir() {
        DesktopImportBook.goBack()
    }

    override fun openImportedBookReader(item: ImportFileItem) {
        val file = (item as? DesktopImportFile)?.file ?: return
        openImportFile(file.absolutePath)
    }

    override fun openImportFile(filePath: String) {
        scope.launch {
            runCatching { FileBook.importLocalFile(filePath) }
                .onSuccess { book ->
                    AppNavigatorProviders.getOrNull()?.push(book.toReadRoute())
                }.onFailure { error ->
                    AppLog.put("导入关联书籍失败: ${error.message}", error)
                }
        }
    }

    // ===== 阅读样式平台能力 =====

    /**
     * 阅读背景内置图片列表 (对照 app 端 [RemoteAssetsUtils.getBgList])。
     * RemoteAssetsUtils 位于 shared jvmAndAndroidMain, 桌面 JVM 直接复用同一下载/缓存链路
     * (bg:// 由 ImageBitmapLoader.jvm 的 RemoteAssetsUtils.getBgCachePath/downloadBgIfNeeded 支撑),
     * 背景文字配置弹窗的内置预设列表 (午后沙滩等) 与 Android 端一致。
     */
    override fun readerBackgroundImageNames(): List<String> = RemoteAssetsUtils.getBgList()

    /**
     * 系统 TTS 设置入口 (朗读设置弹窗"系统TTS设置"项): 各平台打开自己的语音设置页。
     * - Windows: `ms-settings:speech` (设置 → 隐私 → 语音)
     * - macOS: 系统设置 → 辅助功能 → 朗读内容 (Spoken Content)
     * - Linux: 尽力尝试 gnome-control-center, 失败提示不支持
     */
    override fun openTtsSettings() {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("win") -> listOf("cmd", "/c", "start", "", "ms-settings:speech")
            os.contains("mac") -> listOf(
                "open",
                "x-apple.systempreferences:com.apple.preference.universalaccess?SpokenContent"
            )

            os.contains("linux") -> listOf("gnome-control-center", "universal-access")
            else -> null
        }
        if (command == null) {
            unsupported("系统 TTS 设置")
            return
        }
        runCatching { ProcessBuilder(command).start() }.onFailure {
            AppLog.put("打开系统 TTS 设置失败: ${it.message}", it)
            unsupported("系统 TTS 设置")
        }
    }

    // ===== 私有辅助 =====

    /** 上次导出目录 (对照 app 端 ACache "exportBookPath")。 */
    private const val KEY_EXPORT_BOOK_PATH = "exportBookPath"

    private fun resolveLocalFile(bookUrl: String): File = if (bookUrl.startsWith("file:")) {
        // Windows 上 URI.path 是 "/C:/..." 不是合法路径, 必须交给 File(URI) 解析盘符
        runCatching { File(URI(bookUrl)) }.getOrElse { File(bookUrl.removePrefix("file:")) }
    } else {
        File(bookUrl)
    }

    /** 选中书源转 JSON (选中率>=100% 时按当前排序取全量, 与 app 端 saveToFile 的 3 分支等价简化)。 */
    private suspend fun selectedSourcesJson(selection: List<BookSourcePart>): String? {
        val urls = selection.map { it.bookSourceUrl }
        val sources = runCatching { appDb.bookSourceDao.getBookSourcesFix(urls) }.getOrElse {
            Toasters.get().toast("导出书源失败\n${it.message}")
            return null
        }
        // 对照 app 端: 导出前强制关闭危险 API 开关
        sources.forEach { if (it.enableDangerousApi == true) it.enableDangerousApi = false }
        return GSON.toJson(sources)
    }

    private fun saveJsonToPickedFile(defaultName: String, json: String) {
        val file = FileDialogs.pickSaveFile(
            defaultName = defaultName,
            extensions = listOf("json"),
            extensionDesc = "JSON",
            initialDir = runCatching {
                File(DataStorageProviders.get().userExportDir).apply { mkdirs() }
            }.getOrNull()?.takeIf { it.isDirectory },
        ) ?: return
        runCatching { file.writeText(json, Charsets.UTF_8) }
            .onSuccess { Toasters.get().toast("已导出到 ${file.absolutePath}") }
            .onFailure { Toasters.get().toast("导出失败\n${it.message}") }
    }

    /** 书架导出字段 (与 app 端 exportBookshelf 的 13 个字段一致)。 */
    private fun Book.toShelfJsonMap(): Map<String, Any?> = buildMap {
        put("bookUrl", bookUrl)
        put("tocUrl", tocUrl)
        put("origin", origin)
        put("originName", originName)
        put("name", name)
        put("author", author)
        kind?.let { put("kind", it) }
        coverUrl?.let { put("coverUrl", it) }
        customCoverUrl?.let { put("customCoverUrl", it) }
        intro?.let { put("intro", it) }
        customIntro?.let { put("customIntro", it) }
        put("type", type)
        wordCount?.let { put("wordCount", it) }
    }
}
