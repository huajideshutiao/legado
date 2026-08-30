package io.legado.app.ui

import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.copyToClipboard as copyTextToClipboard
import io.legado.app.help.readFromClipboard
import io.legado.app.help.openURL
import io.legado.app.help.source.OhosCheckSource
import io.legado.app.help.toast.Toasters
import io.legado.app.model.Debug
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.File
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.web.WebServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override fun shareText(text: String) {
        services?.sharing?.shareText(text)
    }

    override fun copyToClipboard(text: String) {
        copyTextToClipboard(text)
    }

    // 读系统剪贴板 (对照原版 ContextExtensions getClipText: 主题导入/规则粘贴等 7 场景)
    override fun getClipboardText(): String? = readFromClipboard()

    // 按 bookUrl 查 DB 解析 BookRef, 供 deep link / 文件关联的路由导航
    override suspend fun resolveBookRef(bookUrl: String): BookRef? =
        appDb.bookDao.getBook(bookUrl)?.toRouteRef()

    // ===== Web 服务 (WebServerManager + NativeWebServerPlatform 已下沉并在 OhosProviderRegistry 注册) =====

    // ===== 关于页 =====

    // ===== 书籍详情页 =====

    // 本地书文件字节数 (bookUrl 形如 file:///path, 鸿蒙沙盒为 POSIX 路径, 去 scheme 即可)
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(IoDispatcher) {
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

    // 完整分发链 (压缩包/JSON 一键导入/书籍文件) 见 NativeFileAssociationDispatch, 与 iOS 共用
    override fun openImportFile(filePath: String) {
        scope.launch { NativeFileAssociationDispatch.dispatch(filePath) }
    }

    // ===== 导入本地书 (状态与扫描见 NativeImportBook, 与 iOS 共用) =====

    override fun initImportBookData() = NativeImportBook.init(restoreLast = true)

    override fun importBookItems(): StateFlow<List<ImportFileItem>> = NativeImportBook.items
    override fun importBookPath(): StateFlow<String?> = NativeImportBook.path
    override fun importBookLoading(): StateFlow<Boolean> = NativeImportBook.loading
    override fun importBookEmptyMsgVisible(): StateFlow<Boolean> =
        NativeImportBook.emptyMsgVisible

    // 对照 Android onPickFolder / selectFolder.launch;
    // 复用 OhosPlatformServices.pickDirectory (DocumentViewPicker → 折回 POSIX 路径),
    // 桥接未就绪或用户取消返回 null 时保持原目录不动
    override fun pickImportFolder() {
        scope.launch {
            val path = services?.files?.pickDirectory() ?: return@launch
            NativeImportBook.setRoot(path)
        }
    }

    override fun scanImportFolder() = NativeImportBook.scan()

    // 按文件名导入 js 编辑框: 鸿蒙无命令式文本输入宿主, 经共享 Overlay 弹 TextInputDialog
    // (key="import_file_name", LegadoApp 分支内写 PreferKey.bookImportFileName; 对照 app 端
    // alertImportFileName 的 editTextView + okButton 语义)
    override fun alertImportFileName() {
        AppNavigatorProviders.getOrNull()?.showOverlay(AppOverlay.Dialog("import_file_name"))
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
