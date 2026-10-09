package io.legado.app.ui.root

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Review
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.BookChapterLoader
import io.legado.app.help.book.toggleBookshelfCore
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.info.WebFileImportShared
import io.legado.app.ui.book.read.config.FontItem
import io.legado.app.ui.route.encodeReviewListDialogPayload
import io.legado.app.utils.FlowBus
import io.legado.app.web.WebServerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * 等 Overlay 结果的超时上限: 取消关闭对话框不回传结果, 无超时则协程永久挂起。
 * 10 分钟对"挑封面/选文件"这类用户操作足够宽裕, 又能保证泄漏有界。
 */
private const val OVERLAY_RESULT_TIMEOUT_MS = 10 * 60 * 1000L

/**
 * 内核已下沉、非 Android 三端 (iOS / 鸿蒙 / 桌面) 实现逐字相同的那部分 [PlatformCapabilities]。
 *
 * 三端各自 `: SharedPlatformCapabilities` 即可, 不再各写一份; Android 端有自己的实现
 * (读 AppConfig / BookCover 等), 照常 override 覆盖本层默认。
 *
 * 只放"依赖全在 commonMain / sharedUiMain"的能力: 一行委托到各端叶子源集函数的
 * (openURL / 剪贴板 / NativeImportBook 等) 是平台边界, 留在各端。
 */
interface SharedPlatformCapabilities : PlatformCapabilities {

    /** 各端注入自己的协程作用域 (原各实现里的私有 `scope`)。 */
    val capabilityScope: CoroutineScope

    // ===== Web 服务 =====

    override fun setWebService(enabled: Boolean) {
        capabilityScope.launch {
            runCatching { if (enabled) WebServerManager.start() else WebServerManager.stop() }
                .onFailure { AppLog.put("Web 服务启停失败\n${it.message}", it) }
        }
    }

    // ===== 书源 / 书籍变量弹窗 =====

    override fun showSourceVariableDialog(book: Book) {
        capabilityScope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
            if (source == null) {
                Toasters.get().toast("未找到书源")
                return@launch
            }
            showBookSourceVariableDialog(source)
        }
    }

    override fun showBookSourceVariableDialog(source: BookSource) {
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog(
                key = "sourceVariable",
                payload = encodeSourceVariableOverlayPayload(source),
            )
        )
    }

    override fun showBookVariableDialog(book: Book) {
        capabilityScope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
                ?: return@launch
            AppNavigatorProviders.get().showOverlay(
                AppOverlay.Dialog(
                    key = "bookVariable",
                    payload = encodeBookVariableOverlayPayload(book, source),
                )
            )
        }
    }

    /** 简介里的 js 动作 (对照原版 BookInfoActivity 简介长按 → source.evalJS)。 */
    override fun evalIntroAction(book: Book, js: String) {
        val action = js.trim().ifEmpty { return }
        capabilityScope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
            if (source == null) {
                Toasters.get().toast("未找到书源")
                return@launch
            }
            runCatching { source.evalJS(action) { this["book"] = book } }
                .onFailure { Toasters.get().toast(it.message ?: it::class.simpleName.orEmpty()) }
        }
    }

    override fun addBookSource() {
        AppNavigatorProviders.get().push(AppRoute.BookSourceEdit(""))
    }

    // ===== 书籍详情页 =====

    /** 上架/下架 (走 shared 统一核心 toggleBookshelfCore; 三端均无 Android 的删除确认弹窗)。 */
    override fun toggleBookshelf(
        book: Book,
        inBookshelf: Boolean,
        onComplete: (Boolean?) -> Unit,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
        chapters: List<BookChapter>?,
    ) {
        capabilityScope.launch {
            runCatching { book.toggleBookshelfCore(inBookshelf, chapters) }
                .onSuccess { onComplete(it) }
                .onFailure {
                    AppLog.put("书架操作失败\n${it.message}", it)
                    onComplete(false)
                }
        }
    }

    // ===== webFile 下载导入链路 (对照 app 端 BookInfoViewModel 同名方法) =====
    //
    // 平台无关编排全在 [WebFileImportShared] (构建 webFile 列表 / 下载 / 从压缩包导入),
    // 目录落库统一经 [BookChapterLoader.fetchFromSource] (与阅读页同一条链),
    // 不另写一份"取目录 + 落库"。
    // 平台边界由调用方传入: 等待框 [onWaitDialog]、"去设置目录"动作 [onAction]、
    // 条目选择框与"不支持格式时交系统程序打开"留在各端 (四端 UI 形态不同)。

    /** 导入 webFile 为本地书 (对照 app 端 `BookInfoViewModel.importWebFile`)。 */
    override fun importWebFile(
        book: Book,
        webFile: FileBook.WebFile,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
        success: ((Book) -> Unit)?,
    ) {
        capabilityScope.launch {
            withContext(mainDispatcher) { onWaitDialog(true) }
            try {
                val merged = withContext(IoDispatcher) {
                    val local = WebFileImportShared.importWebFile(book, webFile)
                    BookChapterLoader.fetchFromSource(
                        local,
                        WebFileImportShared.bookSourceOf(local),
                    )
                    local
                }
                withContext(mainDispatcher) { success?.invoke(merged) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                when (e) {
                    is InvalidBooksDirException ->
                        withContext(mainDispatcher) { onAction("selectBooksDir") }

                    else -> AppLog.put("ImportWebFileError\n${e.message}", e, true)
                }
            } finally {
                withContext(mainDispatcher) { onWaitDialog(false) }
            }
        }
    }

    /** 仅下载 webFile 并回传保存引用 (对照 app 端 `BookInfoViewModel.downloadWebFile`)。 */
    override fun downloadWebFile(
        book: Book,
        webFile: FileBook.WebFile,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
        success: ((String) -> Unit)?,
    ) {
        capabilityScope.launch {
            withContext(mainDispatcher) { onWaitDialog(true) }
            try {
                val path = withContext(IoDispatcher) {
                    WebFileImportShared.downloadWebFile(book, webFile)
                }
                withContext(mainDispatcher) { success?.invoke(path) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                when (e) {
                    is InvalidBooksDirException ->
                        withContext(mainDispatcher) { onAction("selectBooksDir") }

                    else -> AppLog.put("DownloadWebFileError\n${e.message}", e, true)
                }
            } finally {
                withContext(mainDispatcher) { onWaitDialog(false) }
            }
        }
    }

    /** 列出压缩包内可导入的条目名 (对照 app 端 `BookInfoViewModel.getArchiveFilesName`)。 */
    override fun getArchiveFilesName(archiveFilePath: String, onSuccess: (List<String>) -> Unit) {
        capabilityScope.launch {
            try {
                val names = withContext(IoDispatcher) {
                    WebFileImportShared.getArchiveFilesName(archiveFilePath)
                }
                withContext(mainDispatcher) { onSuccess(names) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("getArchiveEntriesName Error:\n${e.message}", e, true)
            }
        }
    }

    /** 从压缩包内指定条目导入 (对照 app 端 `BookInfoViewModel.importBookFromArchive`)。 */
    override fun importBookFromArchive(
        archiveFilePath: String,
        archiveEntryName: String,
        book: Book,
        onWaitDialog: (Boolean) -> Unit,
        success: ((Book) -> Unit)?,
    ) {
        capabilityScope.launch {
            withContext(mainDispatcher) { onWaitDialog(true) }
            try {
                val merged = withContext(IoDispatcher) {
                    val imported = WebFileImportShared.importBookFromArchive(
                        archiveFilePath, archiveEntryName, book,
                    )
                    BookChapterLoader.fetchFromSource(
                        imported,
                        WebFileImportShared.bookSourceOf(imported),
                    )
                    imported
                }
                withContext(mainDispatcher) { success?.invoke(merged) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("importArchiveBook Error\n${e.message}", e, true)
            } finally {
                withContext(mainDispatcher) { onWaitDialog(false) }
            }
        }
    }

    /**
     * 从 WebDav 拉本地书的远端更新 (对照 app 端 `BookInfoViewModel.refreshWebDavBook`)。
     *
     * 非 Android 端没有 app 端 [io.legado.app.help.AppWebDav] 的 init 块 (那里启动即 upConfig),
     * 故首次使用时补一次认证 (与 native 端 uploadBook 同款惰性认证); 未配置 WebDav 时
     * upConfig 静默返回, 仅 WebDav 书 (远端地址非空) 会继续走到取远端条目。
     *
     * # 保存位置未设置时的行为 (三分支, 与备份路径同一套)
     * 共享层 [AppWebDavShared.checkBookSaveDir] 只在 [io.legado.app.help.book.effectiveBookSaveDir]
     * 为 null 时抛 [InvalidBooksDirException] (文案 = 原版 `R.string.no_books_dir`)。
     * 桌面/iOS/鸿蒙三端都有平台惯例默认 books 目录 ([io.legado.app.help.storage.DataStorage.defaultBookDir]),
     * 未选保存位置时就用默认目录下载, 不打扰用户; 故下面的“去设置目录”出口在这三端不触发。
     * 仍保留 catch 作为平台默认目录为 null (或 provider 未注册) 时的安全出口: 与三端
     * `importWebFile`/`downloadWebFile` 的 `onAction("selectBooksDir")` 同一入口
     * ([PlatformCapabilities.pickBookTreeUri])、同一偏好键。
     * 选目录是平台 UI, 归主线程; 用户取消 (uri == null) 时不动既有配置、不报错。
     * 异常仍原样上抛 (不吞): 调用方照旧记日志, 只是多了一次用户可见的出口。
     * Android 端不受影响: 它有自建 override ([io.legado.app.ui.main.AndroidPlatformCapabilities]),
     * 行为与原版逐字一致, 且其 defaultBookDir 为 null (保持原版“必须选”)。
     */
    override suspend fun refreshWebDavBook(book: Book) {
        if (AppWebDavShared.authorization == null) AppWebDavShared.upConfig()
        try {
            AppWebDavShared.refreshWebDavBook(book)
        } catch (e: InvalidBooksDirException) {
            val pref = PreferenceProviders.get()
            withContext(mainDispatcher) {
                PlatformCapabilityProviders.get().pickBookTreeUri { uri ->
                    if (uri != null) pref.putString(PreferKey.defaultBookTreeUri, uri)
                }
            }
            throw e
        }
    }

    /** 段评列表 (对照原版 ReviewListDialog), 经 AppOverlay 弹 shared 实现。 */
    override fun showReviewListDialog(
        book: Book,
        chapter: BookChapter?,
        paragraphIndex: Int,
        parentReview: Review?,
    ): Boolean {
        AppNavigatorProviders.get().showOverlay(
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

    // ===== 导出 / 备份开关 =====

    override fun exportUseReplace(): Boolean = PreferenceProviders.get().getBoolean(PreferKey.exportUseReplace, true)

    override fun toggleExportUseReplace() {
        PreferenceProviders.get().putBoolean(PreferKey.exportUseReplace, !exportUseReplace())
    }

    override fun toggleCustomExport() {
        PreferenceProviders.get().putBoolean(PreferKey.enableCustomExport, !enableCustomExport())
    }

    override fun exportToWebDav(): Boolean = PreferenceProviders.get().getBoolean(PreferKey.exportToWebDav, false)

    override fun toggleExportWebDav() {
        PreferenceProviders.get().putBoolean(PreferKey.exportToWebDav, !exportToWebDav())
    }

    override fun setDeleteBookOriginal(value: Boolean) {
        PreferenceProviders.get().putBoolean(LocalConfigKeys.deleteBookOriginal, value)
    }

    override fun setLocalPassword(password: String?) {
        PreferenceProviders.get().putString(LocalConfigKeys.password, password)
    }

    // ===== 主题 / 封面 =====

    override fun showThemeListDialog() {
        AppNavigatorProviders.get().showOverlay(AppOverlay.Dialog("theme_list"))
    }

    override fun showDefaultCoverGallery(isNight: Boolean) {
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog(
                key = "default_cover_gallery",
                payload = if (isNight) "1" else "0",
            )
        )
    }

    override fun refreshDefaultCover() {
        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
    }

    /**
     * 三端无 Android 那套 Activity 重建式换肤, 但 ThemeStore 存的是「已应用」的色值,
     * 光重组读不到新日/夜分支 —— 必须按新 themeMode 重算色 + emit RECREATE 让 AppTheme 重组。
     */
    override fun applyDayNight() {
        ThemeConfigProviders.get().applyThemeMode()
    }

    /** 三端无 ViewConfiguration, 取与 Android 默认接近的固定值。 */
    override fun getScaledTouchSlop(): Int = 10

    // ===== 关于页: 崩溃日志 =====

    /** 崩溃日志 (对照 app 端 showDialogFragment<CrashLogsDialog>), 数据源 = 各端 CrashLogProvider。 */
    override fun showCrashLogs() {
        AppNavigatorProviders.get().showOverlay(AppOverlay.Dialog("crash_logs"))
    }

    // ===== 书架布局 / 底栏配置 =====

    override fun showBookshelfLayoutDialog() {
        AppNavigatorProviders.get().showOverlay(AppOverlay.Dialog("bookshelf_layout"))
    }

    override fun showBottomNavConfigDialog() {
        AppNavigatorProviders.get().showOverlay(AppOverlay.Dialog("bottom_nav_config"))
    }

    // ===== 其它设置: 校验设置 / 直链上传配置 =====

    /**
     * 先同步入栈, 再等它从栈中移除后回调 [onDismiss]。
     *
     * 等"出现"再等"移除"会在对话框于协程被调度前就已关闭时永不满足, onDismiss 丢失且协程挂到宿主销毁。
     */
    override fun showCheckSourceConfigDialog(onDismiss: () -> Unit) {
        val navigator = AppNavigatorProviders.get()
        navigator.showOverlay(AppOverlay.Dialog("check_source_config"))
        capabilityScope.launch {
            navigator.overlays.first { list -> list.none { it.key == "check_source_config" } }
            withContext(mainDispatcher) { onDismiss() }
        }
    }

    override fun showDirectLinkUploadConfigDialog() {
        AppNavigatorProviders.get()
            .showOverlay(AppOverlay.Dialog("direct_link_upload_config"))
    }

    // ===== 换封面源 =====

    /**
     * 用户取消关闭对话框时 payload 为 None 不 emit (见 [AppNavigator.pop]), 只等结果会永久挂起并把
     * 闭包留到宿主销毁, 故加超时兜底, 超时按"用户取消"处理。
     */
    override fun showChangeCoverDialog(book: Book, onCoverSelected: (String) -> Unit) {
        val navigator = AppNavigatorProviders.get()
        navigator.showOverlay(
            AppOverlay.Dialog("change_cover", payload = "${book.name}\n${book.author}")
        )
        capabilityScope.launch {
            val result = withTimeoutOrNull(OVERLAY_RESULT_TIMEOUT_MS) {
                navigator.overlayResults.first { it.key == "change_cover" }
            }
            val payload = result?.payload as? RouteResultPayload.ChangeCover ?: return@launch
            withContext(mainDispatcher) { onCoverSelected(payload.coverUrl) }
        }
    }

    // ===== 阅读样式: 字体列表 =====

    /**
     * pref 字体目录 + 内置字体目录合并, 同名去重 (先扫的 pref 目录优先) 后按名排序
     * (对照 app 端 FontSelectDialog.loadFontFiles)。
     *
     * 支持 SAF content:// 目录的端自行 override。
     */
    override suspend fun scanFontItems(): List<FontItem> = withContext(IoDispatcher) {
        val fontRegex = Regex("(?i).*\\.[ot]tf")
        val items = arrayListOf<FontItem>()
        PreferenceProviders.get().getString(PreferKey.fontFolder)
            .takeIf { it.isNotBlank() }
            ?.let { scanFontDir(items, it.toPath(), fontRegex) }
        scanFontDir(items, DataStorageProviders.get().fontsDir.toPath(), fontRegex)
        items.distinctBy { it.name }.sortedBy { it.name }
    }
}

/** 列举目录内字体文件 (非目录/不存在时不动 items)。 */
private fun scanFontDir(items: MutableList<FontItem>, dir: Path, fontRegex: Regex) {
    val fs = FileSystem.SYSTEM
    fs.listOrNull(dir)?.forEach { child ->
        if (fs.metadataOrNull(child)?.isRegularFile == true &&
            child.name.matches(fontRegex)
        ) {
            items.add(FontItem(child.toString(), child.name))
        }
    }
}
