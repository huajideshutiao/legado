package io.legado.desktop.ui

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.DirectLinkUploadRule
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.Debug
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.source.manage.BookSourceViewModelShared
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.FlowBus
import io.legado.app.utils.GSON
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap
import io.legado.app.utils.toJson
import io.legado.app.web.WebServerManager
import io.legado.desktop.constant.DesktopAppInfo
import io.legado.desktop.help.DesktopAppUpdate
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

    override fun showThemeCustomizeDialog(configIndex: Int?, isNight: Boolean) {
        Toasters.get().toast("当前平台暂不支持：自定义主题")
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

    override fun checkUpdate() {
        scope.launch {
            DesktopAppUpdate.check { info ->
                // 桌面端无自动安装 (无 Intent/DownloadManager), 提示后打开下载页
                Toasters.get().toast("发现新版本 ${info.latestVersion}, 正在打开下载页")
                val url = info.downloadUrl.ifEmpty { "https://github.com/gedoor/legado/releases/latest" }
                runCatching { openExternalUrl(url) }
            }
        }
    }

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

    // 上架/下架 (对照 app 端 BaseReadViewModel.addToBookshelf / delBook, 桌面端无删除确认弹窗)
    override fun toggleBookshelf(
        book: Book,
        inBookshelf: Boolean,
        onComplete: (Boolean?) -> Unit,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
    ) {
        scope.launch {
            runCatching {
                if (inBookshelf) {
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookDao.delete(book)
                    null
                } else {
                    if (book.order == 0) book.order = appDb.bookDao.minOrder() - 1
                    appDb.bookDao.getBook(book.name, book.author)?.let {
                        book.durChapterIndex = it.durChapterIndex
                        book.durChapterPos = it.durChapterPos
                        book.durChapterTitle = it.durChapterTitle
                    }
                    appDb.bookDao.insert(book)
                    true
                }
            }.onSuccess { onComplete(it) }
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

    // 导出书籍正文 (TXT), 目录来自 pref 或现选
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
            if (books.isNotEmpty()) startExport(dir.absolutePath, books)
        }
    }

    /** 用户没选过导出目录时的默认落点 (桌面/legado/books), 创建失败返回 null 走目录选择器。 */
    private fun defaultBookExportDir(): String? = runCatching {
        val dir = File(DataStorageProviders.get().bookExportDir)
        if (dir.isDirectory || dir.mkdirs()) dir.absolutePath else null
    }.getOrNull()

    private fun startExport(dir: String, books: List<Book>) {
        scope.launch {
            Toasters.get().toast("开始导出 ${books.size} 本")
            runCatching { DesktopBookExport.exportTxt(dir, books) }
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
