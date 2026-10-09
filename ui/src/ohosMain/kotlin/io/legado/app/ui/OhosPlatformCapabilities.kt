package io.legado.app.ui

import io.legado.app.constant.AppLog
import kotlin.concurrent.Volatile
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.file.OhosDirAuthorizations
import io.legado.app.help.copyToClipboard as copyTextToClipboard
import io.legado.app.help.readFromClipboard
import io.legado.app.help.openURL
import io.legado.app.help.source.OhosCheckSource
import io.legado.app.help.toast.Toasters
import io.legado.app.help.tts.OhosReadAloudHost
import io.legado.app.help.upLoadToDirectLink
import io.legado.app.model.Debug
import io.legado.app.model.fileBook.FileBook
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.service.defaultTtsEngineConfig
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.info.WebFileImportShared
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.File
import io.legado.app.utils.GSON
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.ScreenInfoProviders
import io.legado.app.utils.toJson
import io.legado.app.web.WebServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 鸿蒙端 [io.legado.app.ui.root.PlatformCapabilities]: 内核已下沉的能力复用 [SharedPlatformCapabilities] (对照 desktop),
 * 依赖弹窗宿主 (分组管理/文本输入/主题列表) 的能力保持 unsupported —
 * 鸿蒙端尚无命令式对话框宿主, 需先补 Compose 对话框层 (本地书导入浏览已由
 * [NativeImportBook] 支持, 仅"按文件名导入 js"文本输入待 shared 补 TextInput Overlay)。
 * 转场动画 spec 不 override, 随 shared 默认 (iOS 式 300ms): 鸿蒙系统默认页面转场
 * 使用弹簧曲线 (spring curve), 时长与物理参数相关且不同设备默认动画不同
 * (查证 OpenHarmony 官方文档 arkts-navigation-animation.md: "默认转场动画使用弹簧曲线,
 * 时长不可控"), 无公开参数可读也无可复刻的稳定值, 鸿蒙端待平台能力接入后再定。
 */
object OhosPlatformCapabilities : NativePlatformCapabilities {
    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    override val capabilityScope: CoroutineScope get() = scope

    private val appDb get() = AppDbProviders.get()
    private val prefs get() = PreferenceProviders.get()
    private val services get() = PlatformServiceProviders.get()
    private var exportBooksJob: Job? = null

    /**
     * 当前显示密度 (px → vp), 由 [io.legado.app.MainOhos] 在根组合里经 [updateDisplayDensity] 注入。
     *
     * 唯一用途是 [isTablet] 的判据: 鸿蒙只有"屏幕物理像素"桥
     * ([OhosNativeBridge.getScreenSizePx], 值 = vp × densityPixels), 而"是否平板"要和安卓端
     * 同口径 (屏幕短边 dp/vp 档位), 换算所需的密度只有 Compose 侧拿得到
     * (与 shared `AppDialogSizes` 用 LocalDensity 把屏幕物理像素换成 dp 同款口径)。
     * 0 = 尚未注入 (根组合之前), [isTablet] 按非平板处理。
     */
    @Volatile
    private var displayDensity: Float = 0f

    /** 平板判据: 屏幕短边 vp 下限 (对照安卓 `SCREENLAYOUT_SIZE_LARGE` 档 ≈ sw480dp)。 */
    private const val TABLET_MIN_SMALLEST_WIDTH_VP = 480f

    /** 注入当前显示密度 (根组合里读 LocalDensity; 密度变化时重注入, 只接受正值)。 */
    fun updateDisplayDensity(density: Float) {
        if (density > 0f) displayDensity = density
    }

    // 鸿蒙由系统统一管理应用生命周期, 无 Activity.finish 等价物;
    // 退出经 OhosNativeBridge.exitApplication → window tsfn → ArkTS UIAbilityContext.terminateSelf()
    // (复用 window 桥, action="exitApplication", 见 EntryAbility onWindowStageCreate 的 window callback)
    override fun exitApplication() {
        OhosNativeBridge.exitApplication()
    }

    override fun openExternalUrl(url: String) {
        openURL(url)
    }

    override fun shareText(text: String) {
        services.sharing.shareText(text)
    }

    override fun copyToClipboard(text: String) {
        copyTextToClipboard(text)
    }

    // 读系统剪贴板 (对照原版 ContextExtensions getClipText: 主题导入/规则粘贴等 7 场景)
    override fun getClipboardText(): String? = readFromClipboard()

    /**
     * 应用版本名 (对照 app 端 AppConst.appInfo.versionName)。
     *
     * 鸿蒙版本号在 app.json5 且由 hvigorfile.ts 在构建阶段覆写, Kotlin/Native 无读取应用包信息的
     * API, 故由 ArkTS 启动时经 [OhosNativeBridge.registerAppVersionFn] 注入; 未注入返回 null,
     * 关于页版本号条目退化为空 summary。
     */
    override fun getAppVersionName(): String? = OhosNativeBridge.getAppVersionName()

    /** 引擎保存后重载正在播放的朗读 (与 iOS 同语义, 对照 ReadAloudConfigDialog 保存后的重建)。 */
    override fun onHttpTtsEdited(engine: HttpTTS) {
        if (OhosReadAloudHost.isRun && defaultTtsEngineConfig() == engine.id.toString()) {
            // 正在播放则重载；暂停时记录重建位置，继续朗读才启用新配置。
            OhosReadAloudHost.play(play = !OhosReadAloudHost.isPause)
        }
    }

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

    // 按 bookUrl 查 DB 解析 BookRef, 供 deep link / 文件关联的路由导航
    override suspend fun resolveBookRef(bookUrl: String): BookRef? =
        appDb.bookDao.getBook(bookUrl)?.toRouteRef()

    /**
     * 是否平板 (对照 ChapterProvider.upLayout 的 appCtx.isPad, "平板/横屏双页" auto 分支)。
     *
     * 判据与安卓端同口径: 屏幕短边 vp >= [TABLET_MIN_SMALLEST_WIDTH_VP] (安卓 `Context.isPad`
     * 的 `screenLayout >= SCREENLAYOUT_SIZE_LARGE` 档, 即 sw480dp 级; 常见手机 360~450vp,
     * 平板 800vp 起; iOS 端用 UIDevice.userInterfaceIdiom 直接取设备形态)。
     * 屏幕尺寸来自既有桥 ([ScreenInfoProviders] / [OhosNativeBridge.getScreenSizePx], 单位物理像素),
     * 密度由根组合注入 —— 鸿蒙侧没有独立的设备形态桥, 物理像素不除密度就会把高密度手机误判成平板。
     * 密度/尺寸任一未就绪时返回 false (按非平板处理, 即不启用双页)。
     */
    override fun isTablet(): Boolean {
        val density = displayDensity
        if (density <= 0f) return false
        val size = runCatching { ScreenInfoProviders.get() }.getOrNull() ?: return false
        val smallestVp = minOf(size.screenWidthPx, size.screenHeightPx) / density
        return smallestVp >= TABLET_MIN_SMALLEST_WIDTH_VP
    }

    // ===== Web 服务 (WebServerManager + NativeWebServerPlatform 已下沉并在 OhosProviderRegistry 注册) =====

    // ===== 关于页 =====

    // ===== 书籍详情页 =====

    // 本地书文件字节数 (bookUrl 形如 file:///path, 鸿蒙沙盒为 POSIX 路径, 去 scheme 即可);
    // bookUrl 可能指向用户授权目录内的原文件 (阅读重定位后不复制), 先激活授权再取属性
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(IoDispatcher) {
        val path = bookUrl.removePrefix("file://")
        OhosDirAuthorizations.ensureActivatedFor(path)
        File(path).length()
    }

    // 阅读重定位 (ReaderScreenModel.relocateLocalBook) 传入的 dirUri 是选目录返回的 POSIX 路径:
    // 先激活授权再走 NativePlatformCapabilities 的 depth 0/1 扫描, 返回保持 file:// 前缀 bookUrl;
    // 激活失败抛 SecurityException (带 errCode/policyCode), 不吞成文件不存在
    override fun findBookFileInDir(dirUri: String, fileName: String): String? {
        OhosDirAuthorizations.ensureActivatedFor(dirUri)
        return super<NativePlatformCapabilities>.findBookFileInDir(dirUri, fileName)
    }

    // ===== webFile 下载导入 (对照 BookInfoActivity 同名方法 / iOS IosPlatformCapabilities) =====
    //
    // 平台无关编排 (构建 webFile 列表 / 下载 / 导入本地 / 压缩包条目) 已下沉:
    // 取件与文件名推导在 WebFileImportShared, 下载导入/压缩包导入在 SharedPlatformCapabilities
    // (目录落库统一走 BookChapterLoader.fetchFromSource)。本端只保留两处平台形态:
    // 条目选择框 (系统对话框桥) 与"不支持格式时交系统程序打开" (无系统应用打开通道, 同 iOS
    // 用系统分享面板把文件交给其它 app)。

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
            if (webFiles.isEmpty()) {
                Toasters.get().toast("Unexpected webFileData")
                return@launch
            }
            // 条目选择框 (对照 app 端 activity.selector(download_and_import_file, webFiles));
            // 取消/关闭 = 未选择, 直接结束 (不弹第二个框)
            val index = selectFromList(
                title = syncGetString("download_and_import_file"),
                items = webFiles.map { it.name },
            ) ?: return@launch
            val webFile = webFiles.getOrNull(index) ?: return@launch
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
                                showArchiveEntrySelect(path, fileNames, book, onWaitDialog, onSuccess)
                            }
                        }
                    }

                else -> showUnsupportedWebFileAlert(book, webFile, onWaitDialog, onAction)
            }
        }
    }

    /** 压缩包内选章 (对照 app 端 `showDecompressFileImportAlert` / iOS presentArchiveEntrySelect)。 */
    private fun showArchiveEntrySelect(
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
        val index = selectFromList(
            title = syncGetString("import_select_book"),
            items = fileNames,
        ) ?: return
        val name = fileNames.getOrNull(index) ?: return
        importBookFromArchive(archiveFilePath, name, book, onWaitDialog) { onSuccess?.invoke(it) }
    }

    /** 不支持的格式: 确认后下载并交其它应用打开 (对照 app 端 alert 的 open_fun 分支)。 */
    private fun showUnsupportedWebFileAlert(
        book: Book,
        webFile: FileBook.WebFile,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
    ) {
        val open = confirm(
            title = syncGetString("draw"),
            message = syncGetString("file_not_supported", webFile.name),
            confirmText = syncGetString("open_fun"),
            cancelText = syncGetString("cancel"),
        )
        if (!open) return
        downloadWebFile(book, webFile, onWaitDialog, onAction) { path ->
            // 鸿蒙无"按扩展名把本地文件交系统应用打开"的通道 (安卓 openFileUri 那条),
            // 用系统分享面板把文件交给其它 app (与 iOS presentShareSheet 同款出口);
            // MIME 用 application/octet-stream: 鸿蒙侧按 UTD 反查, 认不出具体类型也照常
            // 出现在候选应用里 (与 iOS 不带类型同口径), 不自行猜 MIME。
            services.sharing.shareFile(path, "application/octet-stream")
        }
    }

    // 对照 RemoteBookActivity.startRead 的 archive 分支 + Android startReadRemoteArchive:
    // 在书籍保存目录里找已下载的压缩包 (目录与 NativeFileBookAccessor 同源 {filesDir}/books),
    // 找到就交文件关联分发链 (与 NativeImportBook.openReader 同口径走直接导入),
    // 没找到就弹确认框由调用方重新下载后重试。
    override fun startReadRemoteArchive(fileName: String, onNeedDownload: () -> Unit) {
        scope.launch {
            val path = AppFilesDirs.get().filesDir + "/books/" + fileName
            if (File(path).exists()) {
                FileAssociationDispatch.dispatch(path)
                return@launch
            }
            val confirmed = confirm(
                title = syncGetString("draw"),
                message = syncGetString("archive_not_found"),
                confirmText = syncGetString("ok"),
                cancelText = syncGetString("cancel"),
            )
            if (confirmed) onNeedDownload()
        }
    }

    // ===== 书架管理: 导出开关 =====

    override fun exportAllBooks(books: List<Book>) {
        if (books.isEmpty()) {
            Toasters.get().toast("书籍不能为空")
            return
        }
        if (exportBooksJob?.isActive == true) {
            Toasters.get().toast("书籍正在导出")
            return
        }
        exportBooksJob = scope.launch {
            val exportDir = File(AppFilesDirs.get().filesDir, "export")
            if (!exportDir.isDirectory && !exportDir.mkdirs()) {
                Toasters.get().toast("无法创建导出目录")
                return@launch
            }
            val files = mutableListOf<File>()
            val failures = mutableListOf<String>()
            val usedPaths = mutableSetOf<String>()
            val exportType = prefs.getInt(PreferKey.exportType, 0)
            books.forEach { book ->
                try {
                    currentCoroutineContext().ensureActive()
                    val file = NativeBookExporter.exportBookFile(book, exportDir, usedPaths, exportType)
                    files.add(file)
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
            if (files.size == 1) {
                val file = files.single()
                services.sharing.shareFile(file.absolutePath, when (file.name.substringAfterLast('.').lowercase()) {
                    "epub" -> "application/epub+zip"
                    "cbz" -> "application/vnd.comicbook+zip"
                    else -> "text/plain"
                })
            } else if (files.isNotEmpty()) {
                Toasters.get().toast("已导出 ${files.size} 本书到 ${exportDir.absolutePath}")
            }
            if (failures.isNotEmpty()) {
                Toasters.get().toast("${failures.size} 本书导出失败：${failures.take(2).joinToString()}")
            }
        }
    }

    override fun selectExportFolder(books: List<Book>) = exportAllBooks(books)

    override fun showExportConfig() {
        AppNavigatorProviders.get().showOverlay(AppOverlay.Dialog("book_export_format"))
    }

    // ===== 书架管理: 导出 JSON =====

    override fun exportAllUseBookSource() {
        scope.launch {
            val sources = runCatching { appDb.bookDao.getAllUseBookSource() }.getOrElse {
                Toasters.get().toast("导出所用书源失败\n${it.message}")
                return@launch
            }
            saveJson("bookSource.json", GSON.toJson(sources))
        }
    }

    // 导出书架 JSON (字段清单与 app 端 exportBookshelf 一致)
    override fun exportBookshelf(books: List<Book>) {
        if (books.isEmpty()) {
            Toasters.get().toast("书籍不能为空")
            return
        }
        scope.launch { saveJson("bookshelf.json", GSON.toJson(books.map { it.toShelfJsonMap() })) }
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
            saveJson("bookSource.json", json)
        }
    }

    override fun checkBookSource(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        OhosCheckSource.start(selection)
    }

    override fun cancelCheckSource() {
        OhosCheckSource.stop()
        Debug.finishChecking()
    }

    // ===== 其它设置 =====

    // ===== 文件关联 =====

    // 完整分发链 (压缩包/JSON 一键导入/书籍文件) 见 FileAssociationDispatch, 四端共用
    override fun openImportFile(filePath: String) {
        scope.launch { FileAssociationDispatch.dispatch(filePath) }
    }

    // ===== 导入本地书 (状态与扫描见 NativeImportBook, 与 iOS 共用) =====

    override fun initImportBookData() = NativeImportBook.init(restoreLast = true)

    override fun importBookItems(): StateFlow<List<ImportFileItem>> = NativeImportBook.items
    override fun importBookPath(): StateFlow<String?> = NativeImportBook.path
    override fun importBookLoading(): StateFlow<Boolean> = NativeImportBook.loading
    override fun importBookEmptyMsgVisible(): StateFlow<Boolean> =
        NativeImportBook.emptyMsgVisible

    // 对照 Android onPickFolder / selectFolder.launch;
    // 复用 OhosPlatformServices.pickDirectory (DocumentViewPicker 选目录 + 授权持久化 +
    // fileUri 官方转换, 记入授权表供重启后激活), 桥接未就绪/用户取消/目录不可访问时
    // 返回 null 保持原目录不动
    override fun pickImportFolder() {
        scope.launch {
            val path = services.files.pickDirectory() ?: return@launch
            NativeImportBook.setRoot(path)
        }
    }

    override fun scanImportFolder() = NativeImportBook.scan()

    // 按文件名导入 js 编辑框: 鸿蒙无命令式文本输入宿主, 经共享 Overlay 弹 TextInputDialog
    // (key="import_file_name", LegadoApp 分支内写 PreferKey.bookImportFileName; 对照 app 端
    // alertImportFileName 的 editTextView + okButton 语义)
    override fun alertImportFileName() {
        AppNavigatorProviders.get().showOverlay(AppOverlay.Dialog("import_file_name"))
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

    // ===== 私有辅助 =====

    /**
     * 弹带标题的候选列表并返回选中下标 (对照 app 端 `activity.selector` / iOS presentSelectList)。
     *
     * 平台形态: ArkTS 侧 `UIContext.getPromptAction().showDialog` (系统提示对话框),
     * 经 [OhosNativeBridge.invokeDialogSync] (请求走 window tsfn, 结果走 platformEvent) 同步取回。
     * 用户取消/关闭对话框 = 未选择, 桥未就绪或超时同样返回 null (调用方直接结束, 不假选第一项)。
     */
    private fun selectFromList(title: String, items: List<String>): Int? {
        val payload = KS_JSON.encodeToString(
            OhosDialogSelectPayload(title = title, items = items)
        )
        val resultJson = OhosNativeBridge.invokeDialogSync("select", payload) ?: return null
        val resp = runCatching {
            KS_JSON.decodeFromString(OhosDialogResult.serializer(), resultJson)
        }.getOrNull()
        if (resp == null || !resp.ok) {
            AppLog.putDebug("弹候选列表失败: ${resp?.error ?: resultJson}", tag = "ohos-dialog")
            return null
        }
        return resp.index
    }

    /**
     * 弹是-否确认框并返回是否确认 (对照 app 端 `activity.alert { okButton/noButton }`)。
     *
     * 点"否"与取消/关闭 (未选择) 在调用点语义相同 (都不继续), 故统一返回 false。
     */
    private fun confirm(
        title: String,
        message: String?,
        confirmText: String,
        cancelText: String,
    ): Boolean {
        val payload = KS_JSON.encodeToString(
            OhosDialogConfirmPayload(
                title = title,
                message = message,
                confirmText = confirmText,
                cancelText = cancelText,
            )
        )
        val resultJson = OhosNativeBridge.invokeDialogSync("confirm", payload) ?: return false
        val resp = runCatching {
            KS_JSON.decodeFromString(OhosDialogResult.serializer(), resultJson)
        }.getOrNull()
        if (resp == null || !resp.ok) {
            AppLog.putDebug("弹确认框失败: ${resp?.error ?: resultJson}", tag = "ohos-dialog")
            return false
        }
        return resp.confirmed == true
    }

    /**
     * 云端有新备份时的确认框 (标题/正文与 app 端 `MainActivity.backupSync` 的
     * `alert(R.string.restore, R.string.webdav_after_local_restore_confirm)` 逐字同源)。
     *
     * 判定不在本方法内: 调用方拿到
     * [io.legado.app.help.StartupMaintenanceShared.NewBackupCheckResult.NewBackup] 后才调本方法。
     * 整个动作 (弹框 + 恢复) 放在本对象 IO 作用域里跑 —— 鸿蒙对话框是同步阻塞桥
     * ([confirm] → [OhosNativeBridge.invokeDialogSync]), 直接在主线程调会把界面线程堵在
     * runBlocking 上; 恢复走共享恢复入口 [AppWebDavShared.restoreWebDav] (与 app 端
     * `MainViewModel.restoreWebDav` 同一个入口), 失败与 app 端同一口径落日志 + toast。
     *
     * @param name 云端备份文件名 (判定结果携带的 `displayName`)
     */
    fun presentWebDavNewBackupConfirm(name: String) {
        scope.launch {
            val confirmed = confirm(
                title = syncGetString("restore"),
                message = syncGetString("webdav_after_local_restore_confirm"),
                confirmText = syncGetString("ok"),
                cancelText = syncGetString("cancel"),
            )
            if (!confirmed) return@launch
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
    }

    /** 写到 [io.legado.app.ui.root.FilePickerService.saveFile] 给出的沙盒可写路径。 */
    private fun saveJson(defaultName: String, json: String) {
        val path = services.files.saveFile(defaultName) ?: return
        runCatching { File(path).writeText(json) }
            .onSuccess { Toasters.get().toast("已导出到 $path") }
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

// ===== 系统对话框桥跨语言 payload / response (与 ArkTS SysDialogBridgeHandler.ets JSON 协议对齐) =====

/** select 请求 payload (Kotlin → ArkTS)。 */
@Serializable
private data class OhosDialogSelectPayload(
    val title: String,
    val items: List<String>,
)

/** confirm 请求 payload (Kotlin → ArkTS); 按钮文案由本侧按语言给 (syncGetString), ArkTS 不拼文案。 */
@Serializable
private data class OhosDialogConfirmPayload(
    val title: String,
    val message: String? = null,
    val confirmText: String,
    val cancelText: String,
)

/**
 * 对话框响应 (ArkTS → Kotlin)。
 *
 * - select 成功: [ok]=true, [index]=选中下标
 * - confirm 成功: [ok]=true, [confirmed]=是否点了"是"
 * - 取消/关闭: [ok]=true, [cancelled]=true (未选择)
 * - 失败: [ok]=false, [error]=错误信息
 */
@Serializable
private data class OhosDialogResult(
    val ok: Boolean,
    val index: Int? = null,
    val confirmed: Boolean? = null,
    val cancelled: Boolean = false,
    val error: String? = null,
)
