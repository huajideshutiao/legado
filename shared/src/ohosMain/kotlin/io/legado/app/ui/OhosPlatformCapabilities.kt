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
import io.legado.app.help.book.toggleBookshelfCore
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.copyToClipboard as copyTextToClipboard
import io.legado.app.help.openURL
import io.legado.app.help.source.OhosCheckSource
import io.legado.app.help.toast.Toasters
import io.legado.app.model.Debug
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.route.encodeReviewListDialogPayload
import io.legado.app.ui.root.encodeBookVariableOverlayPayload
import io.legado.app.ui.root.encodeSourceVariableOverlayPayload
import io.legado.app.utils.File
import io.legado.app.utils.FlowBus
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.web.WebServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 鸿蒙端 [PlatformCapabilities]: 内核已下沉的能力直接复用 shared 实现 (对照 desktop),
 * 依赖弹窗宿主 (分组管理/文本输入/主题列表/导入书籍浏览) 的能力保持 unsupported —
 * 鸿蒙端尚无命令式对话框宿主, 需先补 Compose 对话框层。
 * 转场动画 spec 不 override, 随 shared 默认 (iOS 式 300ms): 鸿蒙系统默认页面转场
 * 使用弹簧曲线 (spring curve), 时长与物理参数相关且不同设备默认动画不同
 * (查证 OpenHarmony 官方文档 arkts-navigation-animation.md: "默认转场动画使用弹簧曲线,
 * 时长不可控"), 无公开参数可读也无可复刻的稳定值, 鸿蒙端待平台能力接入后再定。
 */
object OhosPlatformCapabilities : PlatformCapabilities {
    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    private val appDb get() = AppDbProviders.get()
    private val prefs get() = PreferenceProviders.get()
    private val services get() = PlatformServiceProviders.getOrNull()

    // 鸿蒙由系统统一管理应用生命周期, 无 Activity.finish 等价物;
    // 退出经 OhosNativeBridge.exitApplication → window tsfn → ArkTS UIAbilityContext.terminateSelf()
    // (复用 window 桥, action="exitApplication", 见 EntryAbility onWindowStageCreate 的 window callback)
    override fun exitApplication() {
        OhosNativeBridge.exitApplication()
    }

    override fun openExternalUrl(url: String) {
        openURL(url)
    }

    override fun openWebView(url: String, sourceKey: String, sourceName: String) {
        // 移动端保留内嵌 WebViewRoute 路由语义 (对话框内嵌)
        AppNavigatorProviders.getOrNull()?.push(AppRoute.WebView(url, sourceKey, sourceName))
    }

    override fun shareText(text: String) {
        services?.sharing?.shareText(text)
    }

    override fun copyToClipboard(text: String) {
        copyTextToClipboard(text)
    }

    // 按 bookUrl 查 DB 解析 BookRef, 供 deep link / 文件关联的路由导航
    override suspend fun resolveBookRef(bookUrl: String): BookRef? =
        appDb.bookDao.getBook(bookUrl)?.toRouteRef()

    // AppTheme 直接订阅 ThemeStore/AppConfig, 日夜切换即重组, 无 Activity.recreate 需求
    override fun applyDayNight() = Unit

    // ===== Web 服务 (WebServerManager + NativeWebServerPlatform 已下沉并在 OhosProviderRegistry 注册) =====

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

    // 鸿蒙端无 assets, 直接打开仓库上的文档 (对照 desktop showMdFile)
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

    // 上架/下架 (DB 逻辑统一走 shared toggleBookshelfCore, 与桌面/iOS/Android 一致;
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

    // 本地书文件字节数 (bookUrl 形如 file:///path, 鸿蒙沙盒为 POSIX 路径, 去 scheme 即可)
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(IoDispatcher) {
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
            saveJson("bookSource.json", json)
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

    override fun checkBookSource(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        OhosCheckSource.start(selection)
    }

    override fun cancelCheckSource() {
        OhosCheckSource.stop()
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

    // 刷新默认封面缓存: shared 图集解析已按 raw 串记忆化自动失效,
    // 广播书架刷新让封面槽重组重读即可 (app 端清 Drawable 解码缓存)
    override fun refreshDefaultCover() {
        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
    }

    override fun pickBookTreeUri(onSelected: (String?) -> Unit) {
        scope.launch { onSelected(services?.files?.pickDirectory()) }
    }

    // ===== 文件关联 =====

    // 完整分发链 (压缩包/JSON 一键导入/书籍文件) 见 NativeFileAssociationDispatch, 与 iOS 共用
    override fun openImportFile(filePath: String) {
        scope.launch { NativeFileAssociationDispatch.dispatch(filePath) }
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

    /** 写到 [io.legado.app.ui.root.FilePickerService.saveFile] 给出的沙盒可写路径。 */
    private fun saveJson(defaultName: String, json: String) {
        val path = services?.files?.saveFile(defaultName) ?: return
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
