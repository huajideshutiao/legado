package io.legado.app.ui.main

import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.net.Uri
import android.view.ViewConfiguration
import androidx.core.net.toUri
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.appInfo
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CrashHandler
import io.legado.app.help.IntentHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.update.AppUpdate
import io.legado.app.model.BookCover
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.service.WebService
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.ui.book.import.local.ImportBookViewModel
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.config.CheckSourceConfig
import io.legado.app.ui.config.DefaultCoverGalleryDialog
import io.legado.app.ui.config.DirectLinkUploadConfig
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.dialog.showBookVariableDialog
import io.legado.app.ui.widget.dialog.showSourceVariableDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.FlowBus
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.find
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isUri
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.restart
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

class AndroidPlatformCapabilities(
    private val activity: MainActivity,
) : PlatformCapabilities {

    // 导入书籍: 复用 ImportBookViewModel 维护 rootDoc/subDocs 状态 (对照 ImportBookActivity)
    // 经 ViewModelProvider 绑定 activity ViewModelStore, lifecycle 由 activity 管理
    private val importViewModel by lazy {
        ViewModelProvider(activity).get(ImportBookViewModel::class.java)
    }
    private var scanDocJob: Job? = null

    override fun exitApplication() {
        activity.finish()
    }

    override fun openExternalUrl(url: String) {
        activity.openUrl(url)
    }

    override fun shareText(text: String) {
        activity.share(text)
    }

    // 按 bookUrl 查 DB 解析为 BookRef, 供 LaunchRequest.OpenBook/OpenBookInfo/OpenReader 路由导航
    override suspend fun resolveBookRef(bookUrl: String): BookRef? =
        appDb.bookDao.getBook(bookUrl)?.toRouteRef()

    // 对照 ThemeConfig.applyDayNight
    override fun applyDayNight() {
        ThemeConfig.applyDayNight(activity)
    }

    // 对照 Context.sendToClip
    override fun copyToClipboard(text: String) {
        activity.sendToClip(text)
    }

    // 对照 WebService.hostAddress
    override fun getWebServiceUrl(): String? =
        WebService.hostAddress.takeIf { it.isNotEmpty() }

    // 对照 WebService.isRun
    override fun isWebServiceRunning(): Boolean = WebService.isRun

    // 对照 WebService.start / WebService.stop
    override fun setWebService(enabled: Boolean) {
        if (enabled) WebService.start(activity) else WebService.stop(activity)
    }

    // 对照 MyTab FlowBus.withSticky(EventBus.WEB_SERVICE).collect, 桥接到 StateFlow
    private val webServiceRunningState by lazy {
        MutableStateFlow(WebService.isRun).also { state ->
            activity.lifecycleScope.launch(IO) {
                FlowBus.withSticky(EventBus.WEB_SERVICE).collect { running ->
                    if (running is Boolean) state.value = running
                }
            }
        }
    }

    override val webServiceState: StateFlow<Boolean>? get() = webServiceRunningState

    // 对照 BookInfoEditActivity.onChangeCoverSource + coverChangeTo
    override fun showChangeCoverDialog(book: Book, onCoverSelected: (String) -> Unit) {
        activity.pendingCoverChangeCallback = onCoverSelected
        activity.showDialogFragment(ChangeCoverDialog(book.name, book.author))
    }

    // 对照 DefaultCoverGalleryDialog
    override fun showDefaultCoverGallery(isNight: Boolean) {
        activity.showDialogFragment(DefaultCoverGalleryDialog(isNight))
    }

    // 对照 BookCover.upDefaultCover
    override fun refreshDefaultCover() {
        BookCover.upDefaultCover()
    }

    // 对照 IntentHelp.openTTSSetting
    override fun openTtsSettings() {
        IntentHelp.openTTSSetting()
    }

    // 对照 AppContextWrapper.getFontScale, 返回 %.1f 字符串供 shared 端 %s 替换
    override fun getFontScale(): String? =
        String.format("%.1f", AppContextWrapper.getFontScale(activity))

    // 对照 ViewConfiguration.get(ctx).scaledTouchSlop
    override fun getScaledTouchSlop(): Int =
        ViewConfiguration.get(activity).scaledTouchSlop

    // 对照 ImportBookActivity.onPickFolder / selectFolder.launch
    override fun pickImportFolder() {
        activity.launchImportFolderPicker()
    }

    override fun openImportFile(filePath: String) {
        val uri = Uri.parse(filePath)
        activity.supportFragmentManager.commit {
            add(
                io.legado.app.ui.association.FileAssociationFragment(uri),
                "FileAssociationFragment",
            )
        }
    }

    // 对照 ImportBookActivity.onScanFolder / scanFolder
    override fun scanImportFolder() {
        ensureRootDoc()
        importViewModel.rootDoc?.let { doc ->
            val lastDoc = importViewModel.subDocs.lastOrNull() ?: doc
            scanDocJob?.cancel()
            scanDocJob = activity.lifecycleScope.launch(IO) {
                importViewModel.scanDoc(lastDoc)
            }
        }
    }

    // 对照 ImportBookActivity.onAlertImportFileName / alertImportFileName
    override fun alertImportFileName() {
        activity.alert(R.string.import_file_name) {
            setMessage("使用js处理文件名变量src，将书名作者分别赋值到变量name author")
            val getText = editTextView(hint = "js", text = AppConfig.bookImportFileName ?: "")
            okButton {
                AppConfig.bookImportFileName = getText()
            }
            cancelButton()
        }
    }

    // 对照 ImportBookActivity.onAddSelectionToBookshelf / addSelectionToBookshelf
    override fun addImportSelectionToBookshelf(
        items: List<ImportFileItem>,
        onComplete: () -> Unit
    ) {
        val books = items.mapNotNull { it as? ImportBook }.toHashSet()
        if (books.isEmpty()) {
            onComplete()
            return
        }
        importViewModel.addToBookshelf(books) {
            books.forEach { it.isOnBookShelf = true }
            onComplete()
        }
    }

    // 对照 ImportBookActivity.onSearchTextChange / viewModel.updateCallBackFlow
    override fun updateImportBookFilter(key: String) {
        importViewModel.updateCallBackFlow(key)
    }

    // 对照 ImportBookActivity.upSort: 更新 sort + 持久化 + 非扫描中重排当前列表
    override fun updateImportBookSort(sort: Int) {
        importViewModel.sort = sort
        AppConfig.localBookImportSort = sort
        if (scanDocJob?.isActive != true) {
            importViewModel.dataCallback?.upAdapter()
        }
    }

    // 对照 ImportBookActivity.onItemClick isDir 分支 / startRead
    override fun openImportedBookReader(item: ImportFileItem) {
        (item as? ImportBook)?.let { startRead(it.file) }
    }

    // 对照 ImportBookActivity.onItemClick isDir 分支 / nextDoc
    override fun navigateImportDir(item: ImportFileItem) {
        (item as? ImportBook)?.let { nextDoc(it.file) }
    }

    // 对照 ImportBookActivity.onItemClick isUpDir 分支 / goBackDir
    override fun goBackImportDir() {
        goBackDir()
    }

    // 对照 AboutActivity.onCheckUpdate / AppUpdate.check
    override fun checkUpdate() {
        AppUpdate.check(activity.lifecycleScope, activity)
    }

    // 对照 AboutActivity.onShowCrashLogs / showDialogFragment<CrashLogsDialog>
    override fun showCrashLogs() {
        activity.showDialogFragment<CrashLogsDialog>()
    }

    // 对照 AboutActivity.onSaveLog / saveLog
    override fun saveLog() {
        saveLogInternal()
    }

    // 对照 AboutActivity.onCreateHeapDump / createHeapDump
    override fun createHeapDump() {
        createHeapDumpInternal()
    }

    // 对照 AboutActivity.onShowMdFile / showMdFile
    override fun showMdFile(title: String, fileName: String) {
        showMdFileInternal(title, fileName)
    }

    // ===== 书籍详情页平台能力: 对照 BookInfoActivity 同名方法 =====

    // 对照 BookInfoActivity.onClearCache / viewModel.clearCache (直接调 BookHelp)
    override fun clearBookCache(book: Book) {
        activity.lifecycleScope.launch(IO) {
            BookHelp.clearCache(book)
        }
    }

    // 对照 BookInfoActivity.onSetBookVariable / book.showBookVariableDialog (需查源)
    override fun showBookVariableDialog(book: Book) {
        activity.lifecycleScope.launch(IO) {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            activity.runOnUiThread {
                book.showBookVariableDialog(activity, source)
            }
        }
    }

    // 对照 BookInfoActivity.onSetSourceVariable / source.showSourceVariableDialog (需查源)
    override fun showSourceVariableDialog(book: Book) {
        activity.lifecycleScope.launch(IO) {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            if (source == null) {
                activity.toastOnUi(R.string.error_no_source)
                return@launch
            }
            activity.runOnUiThread {
                source.showSourceVariableDialog(activity)
            }
        }
    }

    // 对照 BookInfoActivity.onDispatchIntroAction / source.evalJS (需查源)
    override fun evalIntroAction(book: Book, js: String) {
        val action = js.trim().ifEmpty { return }
        activity.lifecycleScope.launch(IO) {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            if (source == null) {
                activity.toastOnUi(R.string.error_no_source)
                return@launch
            }
            try {
                source.evalJS(action) {
                    this["book"] = book
                }
            } catch (e: Exception) {
                activity.toastOnUi(e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }

    // 对照 BookInfoActivity.upWordCount 内 FileDoc.fromFile(book.bookUrl).size
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(IO) {
        FileDoc.fromFile(bookUrl).size
    }

    // 对照 AppConst.appInfo.versionName
    override fun getAppVersionName(): String? = AppConst.appInfo.versionName

    // 对照 BookInfoActivity.Content 内 LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE
    override fun isLandscape(): Boolean =
        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 对照 BookInfoActivity.Content 内 setLightStatusBar(if (useDevFeat) isDarkTheme else false)
    override fun setLightStatusBarForBookInfo(useDevFeat: Boolean, isDarkTheme: Boolean) {
        activity.setLightStatusBar(if (useDevFeat) isDarkTheme else false)
    }

    // ===== 书架管理平台能力: 对照 BookshelfManageActivity 同名方法/状态 =====

    // 对照 BookshelfManageActivity.exportUseReplace (AppConfig 读取)
    override fun exportUseReplace(): Boolean = AppConfig.exportUseReplace

    // 对照 BookshelfManageActivity.enableCustomExportChecked
    override fun enableCustomExport(): Boolean = AppConfig.enableCustomExport

    // 对照 BookshelfManageActivity.exportToWebDav
    override fun exportToWebDav(): Boolean = AppConfig.exportToWebDav

    // 对照 BookshelfManageActivity.toggleEnableReplace
    override fun toggleExportUseReplace() {
        AppConfig.exportUseReplace = !AppConfig.exportUseReplace
    }

    // 对照 BookshelfManageActivity.toggleCustomExport
    override fun toggleCustomExport() {
        AppConfig.enableCustomExport = !AppConfig.enableCustomExport
    }

    // 对照 BookshelfManageActivity.toggleExportWebDav
    override fun toggleExportWebDav() {
        AppConfig.exportToWebDav = !AppConfig.exportToWebDav
    }

    // 对照 LocalConfig.deleteBookOriginal 读取
    override fun getDeleteBookOriginal(): Boolean = LocalConfig.deleteBookOriginal

    // 对照 LocalConfig.deleteBookOriginal 赋值
    override fun setDeleteBookOriginal(value: Boolean) {
        LocalConfig.deleteBookOriginal = value
    }

    // 对照 BookshelfManageActivity.exportAllUseBookSource / viewModel.saveAllUseBookSourceToFile
    override fun exportAllUseBookSource() {
        Coroutine.async {
            val path = "${appCtx.filesDir}/shareBookSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            val sources = appDb.bookDao.getAllUseBookSource()
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, sources)
            }
            file
        }.onSuccess { file ->
            activity.launchExportDir("bookSource.json", file, "application/json")
        }.onError {
            activity.toastOnUi(it.stackTraceStr)
        }
    }

    // 对照 BookshelfManageActivity.exportAll
    override fun exportAllBooks(books: List<Book>) {
        val path = ACache.get().getAsString("exportBookPath")
        if (path.isNullOrEmpty()) {
            selectExportFolder(books)
        } else {
            activity.startExportBooks(path, books)
        }
    }

    // 对照 BookshelfManageActivity.exportBookshelf / viewModel.exportBookshelf
    @OptIn(ExperimentalSerializationApi::class)
    override fun exportBookshelf(books: List<Book>) {
        Coroutine.async {
            if (books.isEmpty()) throw NoStackTraceException("书籍不能为空")
            val path = "${appCtx.filesDir}/bookshelf.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            // 对齐原 GSON 行为: prettyPrint + 2 空格缩进 + 不序列化 null 字段
            val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
            val jsonArray = buildJsonArray {
                books.forEach {
                    add(buildJsonObject {
                        put("bookUrl", it.bookUrl)
                        put("tocUrl", it.tocUrl)
                        put("origin", it.origin)
                        put("originName", it.originName)
                        put("name", it.name)
                        put("author", it.author)
                        it.kind?.let { v -> put("kind", v) }
                        it.coverUrl?.let { v -> put("coverUrl", v) }
                        it.customCoverUrl?.let { v -> put("customCoverUrl", v) }
                        it.intro?.let { v -> put("intro", v) }
                        it.customIntro?.let { v -> put("customIntro", v) }
                        put("type", it.type)
                        it.wordCount?.let { v -> put("wordCount", v) }
                    })
                }
            }
            FileOutputStream(file).use { out ->
                out.write(
                    json.encodeToString(JsonArray.serializer(), jsonArray)
                        .toByteArray(Charsets.UTF_8)
                )
            }
            file
        }.onSuccess { file ->
            activity.launchExportDir("bookshelf.json", file, "application/json")
        }.onError {
            activity.toastOnUi("导出书籍出错\n${it.localizedMessage}")
        }
    }

    // 对照 BookshelfManageActivity.selectExportFolder
    override fun selectExportFolder(books: List<Book>) {
        val path = ACache.get().getAsString("exportBookPath")
        activity.pendingExportBooks = books
        activity.launchExportFolderPicker(path)
    }

    // ===== 书源管理平台能力: 对照 BookSourceActivity 同名方法 =====

    // 对照 BookSourceActivity.addBookSource: 新建书源走导航 push BookSourceEdit (sourceUrl 空串表新建)
    override fun addBookSource() {
        AppNavigatorProviders.getOrNull()?.push(AppRoute.BookSourceEdit(""))
    }

    // 对照 BookSourceActivity.showGroupManage
    override fun showBookSourceGroupManage() {
        activity.showDialogFragment<GroupManageDialog>()
    }

    // 对照 BookSourceActivity.cancelCheckSource (CheckSource.stop + Debug.finishChecking)
    override fun cancelCheckSource() {
        CheckSource.stop(activity)
        Debug.finishChecking()
    }

    // ===== 书源编辑平台能力: 对照 BookSourceEditActivity 同名方法 =====

    // 对照 BookSourceEditActivity.login / source.showLoginDialog (route 已先 save)
    override fun showBookSourceLogin(source: BookSource) {
        source.showLoginDialog()
    }

    // 对照 BookSourceEditActivity.setSourceVariable / source.showSourceVariableDialog (route 已先 save)
    override fun showBookSourceVariableDialog(source: BookSource) {
        source.showSourceVariableDialog(activity)
    }

    // ===== 其它设置平台能力: 对照 OtherConfigHost 同名方法 =====

    // 对照 OtherConfigHost.alertLocalPassword okButton: LocalConfig.password = getText()
    override fun setLocalPassword(password: String?) {
        LocalConfig.password = password
    }

    // 对照 OtherConfigHost.localBookTreeSelect.launch DIR_SYS, 回调由 MainActivity 桥接
    override fun pickBookTreeUri(onSelected: (String?) -> Unit) {
        activity.pendingBookTreeUriCallback = onSelected
        activity.launchBookTreeUriPicker()
    }

    // 对照 OtherConfigHost.onCheckSource: showDialogFragment<CheckSourceConfig>
    // BaseComposeDialogFragment.setOnDismissListener 提供 dismiss 回调
    override fun showCheckSourceConfigDialog(onDismiss: () -> Unit) {
        val dialog = CheckSourceConfig()
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { onDismiss() })
        dialog.show(activity.supportFragmentManager, "checkSourceConfig")
    }

    // 对照 OtherConfigHost.onUploadRule: showDialogFragment<DirectLinkUploadConfig>
    override fun showDirectLinkUploadConfigDialog() {
        activity.showDialogFragment<DirectLinkUploadConfig>()
    }

    // 对照 ConfigViewModel.clearWebViewData: 删 webview 目录 + toast + delay + restart
    override fun clearWebViewData() {
        Coroutine.async {
            FileUtils.delete(activity.getDir("webview", Context.MODE_PRIVATE))
            FileUtils.delete(activity.getDir("hws_webview", Context.MODE_PRIVATE), true)
            activity.toastOnUi(R.string.clear_webview_data_success)
            delay(3000)
            appCtx.restart()
        }.onError {
            AppLog.put("清理 WebView 数据失败\n${it.localizedMessage}", it)
        }
    }

    // ===== 导入书籍私有辅助: 复刻 ImportBookActivity 同名方法 =====

    /** 首次访问导入相关方法时按 pref 初始化 rootDoc (对照 ImportBookActivity.initRootDoc) */
    private fun ensureRootDoc() {
        if (importViewModel.rootDoc != null) return
        val lastPath = AppConfig.importBookPath
        if (lastPath.isNullOrBlank()) {
            activity.launchImportFolderPicker()
            return
        }
        val rootUri = if (lastPath.isUri()) {
            lastPath.toUri()
        } else {
            Uri.fromFile(File(lastPath))
        }
        kotlin.runCatching {
            if (rootUri.isContentScheme()) {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(activity, rootUri)
            } else {
                androidx.documentfile.provider.DocumentFile.fromFile(File(rootUri.path!!))
            }?.let { doc ->
                if (!doc.name.isNullOrEmpty() && doc.isDirectory) {
                    importViewModel.subDocs.clear()
                    importViewModel.rootDoc = FileDoc.fromDocumentFile(doc)
                }
            }
        }.onFailure {
            activity.launchImportFolderPicker()
        }
    }

    @Synchronized
    private fun nextDoc(fileDoc: FileDoc) {
        importViewModel.subDocs.add(fileDoc)
        upPath()
    }

    @Synchronized
    private fun goBackDir(): Boolean {
        return if (importViewModel.subDocs.isNotEmpty()) {
            importViewModel.subDocs.removeAt(importViewModel.subDocs.lastIndex)
            upPath()
            true
        } else {
            false
        }
    }

    @Synchronized
    private fun upPath() {
        importViewModel.rootDoc?.let {
            scanDocJob?.cancel()
            upDocs(it)
        }
    }

    private fun upDocs(rootDoc: FileDoc) {
        var lastDoc = rootDoc
        for (doc in importViewModel.subDocs) {
            lastDoc = doc
        }
        importViewModel.loadDoc(lastDoc)
    }

    /** 对照 ImportBookActivity.startRead (压缩包分支依赖 Activity 专属 selector, 暂不实现) */
    private fun startRead(fileDoc: FileDoc) {
        if (!ArchiveUtils.isArchive(fileDoc.name)) {
            runBlocking { appDb.bookDao.getBookByFileName(fileDoc.name) }?.let {
                val filePath = fileDoc.toString()
                if (it.bookUrl != filePath) {
                    it.bookUrl = filePath
                    runBlocking { appDb.bookDao.insert(it) }
                }
                // 内部导航: 按书籍类型分流阅读类路由
                AppNavigatorProviders.getOrNull()?.push(it.toReadRoute())
            }
        }
    }

    // ===== 关于页私有辅助: 复刻 AboutActivity 同名 private 方法 =====

    private fun showMdFileInternal(title: String, fileName: String) {
        val mdText = runCatching {
            activity.assets.open(fileName).bufferedReader().use { it.readText() }
        }.getOrNull() ?: javaClass.classLoader
            ?.getResourceAsStream(fileName)
            ?.bufferedReader()
            ?.use { it.readText() }

        if (mdText != null) {
            activity.showDialogFragment(TextDialog(title, mdText, TextDialog.Mode.MD))
        } else {
            val path = if (fileName == "LICENSE.md") "LICENSE" else "app/src/main/assets/$fileName"
            activity.openUrl("https://github.com/huajideshutiao/legado/blob/master/$path")
        }
    }

    private fun saveLogInternal() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordLog) {
                appCtx.toastOnUi("未开启日志记录，请去其他设置里打开记录日志")
                delay(3000)
            }
            val doc = FileDoc.fromUri(backupPath.toUri(), true)
            copyLogs(doc)
            copyHeapDump(doc)
            appCtx.toastOnUi("已保存至备份目录")
        }.onError {
            AppLog.put("保存日志出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun createHeapDumpInternal() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordHeapDump) {
                appCtx.toastOnUi("未开启堆转储记录，请去其他设置里打开记录堆转储")
                delay(3000)
            }
            appCtx.toastOnUi("开始创建堆转储")
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(backupPath.toUri(), true)
            if (!copyHeapDump(doc)) {
                appCtx.toastOnUi("未找到堆转储文件")
            } else {
                appCtx.toastOnUi("已保存至备份目录")
            }
        }.onError {
            AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
        }
    }

    private fun copyLogs(doc: FileDoc) {
        val cacheDir = appCtx.externalCache
        val logFiles = File(cacheDir, "logs")
        val crashFiles = File(cacheDir, "crash")
        val logcatFile = File(cacheDir, "logcat.txt")

        dumpLogcat(logcatFile)

        val zipFile = File(cacheDir, "logs.zip")
        ZipUtils.zipFiles(arrayListOf(logFiles, crashFiles, logcatFile), zipFile)

        doc.find("logs.zip")?.delete()

        zipFile.inputStream().use { input ->
            doc.createFileIfNotExist("logs.zip").openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        zipFile.delete()
    }

    private fun copyHeapDump(doc: FileDoc): Boolean {
        val heapFile = FileDoc.fromFile(File(appCtx.externalCache, "heapDump")).list()
            ?.firstOrNull() ?: return false
        doc.find("heapDump")?.delete()
        val heapDumpDoc = doc.createFolderIfNotExist("heapDump")
        heapFile.openInputStream().getOrNull()?.use { input ->
            heapDumpDoc.createFileIfNotExist(heapFile.name).openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        return true
    }

    private fun dumpLogcat(file: File) {
        try {
            val process = Runtime.getRuntime().exec("logcat -d")
            file.outputStream().use {
                process.inputStream.copyTo(it)
            }
        } catch (e: Exception) {
            AppLog.put("保存Logcat失败\n$e", e)
        }
    }
}
