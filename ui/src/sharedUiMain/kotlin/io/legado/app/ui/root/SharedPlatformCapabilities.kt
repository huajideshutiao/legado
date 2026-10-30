package io.legado.app.ui.root

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.DirUsage
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Review
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.exception.LocalBookSourceFileDeleteException
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.delBookCore
import io.legado.app.help.book.toggleBookshelfCore
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.info.WebFileImportShared
import io.legado.app.ui.book.manage.reportLocalBookSourceFileDeleteFailure
import io.legado.app.ui.book.read.config.FontItem
import io.legado.app.ui.book.read.config.importFontFileToInternalDir
import io.legado.app.ui.route.encodeReviewListDialogPayload
import io.legado.app.ui.widget.dialog.WaitDialogHandle
import io.legado.app.ui.withLocalDirAccess
import io.legado.app.utils.FlowBus
import io.legado.app.web.WebServerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
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
            // 提示与弹层是界面动作, 回主线程; 能力作用域是 IO, 弹层栈是"先读后写"。
            withContext(mainDispatcher) {
                if (source == null) {
                    Toasters.get().toast("未找到书源")
                    return@withContext
                }
                showBookSourceVariableDialog(source)
            }
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
            // 弹层是界面动作, 回主线程; 能力作用域是 IO, 弹层栈是"先读后写"。
            withContext(mainDispatcher) {
                AppNavigatorProviders.get().showOverlay(
                    AppOverlay.Dialog(
                        key = "bookVariable",
                        payload = encodeBookVariableOverlayPayload(book, source),
                    )
                )
            }
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

    /**
     * 上架/下架。
     *
     * 下架没有确认框的调用方 (视频页) 不删源文件 (对照原版 `VideoViewModel.delBook` 固定 `false`),
     * 确认框在调用方手里 (详情页) 的走 [deleteBookshelfBook] 带勾选值。
     */
    override fun toggleBookshelf(
        book: Book,
        inBookshelf: Boolean,
        scope: CoroutineScope,
        onComplete: (Boolean?) -> Unit,
        onWaitDialog: (Boolean, WaitDialogHandle) -> Unit,
        stages: ImportStageHost,
        chapters: List<BookChapter>?,
    ) {
        if (inBookshelf) {
            deleteBookshelfBook(book, deleteOriginal = false, onComplete = onComplete)
            return
        }
        // onComplete 是界面回调 (调用方在其中 pop 路由 / 派发状态), 回主线程; 能力作用域是 IO。
        scope.launch {
            runCatching { book.toggleBookshelfCore(inBookshelf = false, chapters = chapters) }
                .onSuccess { withContext(mainDispatcher) { onComplete(it) } }
                .onFailure {
                    AppLog.put("书架操作失败\n${it.message}", it)
                    withContext(mainDispatcher) { onComplete(false) }
                }
        }
    }

    /**
     * 下架: 删章节 + 删记录 + 按 [deleteOriginal] 删本地书源文件。
     *
     * 源文件删不掉时如实提示 (与书架删除同一文案), 不勾选时只下架、不碰文件。
     */
    override fun deleteBookshelfBook(
        book: Book,
        deleteOriginal: Boolean,
        onComplete: (Boolean?) -> Unit,
    ) {
        // onComplete 是界面回调 (调用方在其中 pop 路由 / 派发状态), 回主线程; 能力作用域是 IO。
        capabilityScope.launch {
            runCatching { book.delBookCore(deleteOriginal) }
                .onSuccess { withContext(mainDispatcher) { onComplete(null) } }
                .onFailure { e ->
                    AppLog.put("删除书籍失败\n${e.message}", e)
                    // 只有源文件没删掉才是"库里已删、文件还在" (见 delBookCore), 与书架删除同一文案
                    if (e is LocalBookSourceFileDeleteException) {
                        reportLocalBookSourceFileDeleteFailure(book, e)
                    }
                    withContext(mainDispatcher) { onComplete(false) }
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
     * # 保存位置未设置时的行为
     * 共享层 [AppWebDavShared.checkBookSaveDir] 在 [io.legado.app.help.book.effectiveBookSaveDir]
     * 为 null 时抛 [InvalidBooksDirException] (文案 = 原版 `R.string.no_books_dir`);
     * 下面的“去设置目录”出口把它转成目录选择器 —— 与 webFile 下载导入链的
     * "保存位置未设"出口同一入口
     * ([PlatformCapabilities.pickBookTreeUri])、同一偏好键。
     * 选目录是平台 UI, 归主线程; 用户取消 = 本次正常终结 (不动既有配置, 不报假失败).
     * 选定后**先重试本次远端更新再返回** —— 本方法在这个阶段挂住等真回执:
     * 原先拉起选择器后立即上抛, 抛出的瞬间父 Job 就结了, 选择器随之被撤,
     * 用户真选了目录也等于白选 (而这里恰好是唯一能把目录设好的入口)。
     * 其它异常仍原样上抛 (不吞)。
     */
    override suspend fun refreshWebDavBook(book: Book) {
        if (AppWebDavShared.authorization == null) AppWebDavShared.upConfig()
        try {
            AppWebDavShared.refreshWebDavBook(book)
        } catch (e: InvalidBooksDirException) {
            // 取本 suspend 调用的真实宿主请求 Job (withContext 之内的临时 Job 会随块退出立即撤掉刚起的选择)
            val pickerParent = currentCoroutineContext()[Job]
            // 选定 = true; 用户取消 = false (本次正常终结, 既有目录不动); 真实失败由下面的
            // catch 把选择器原异常抛回原等待者
            val selected = try {
                reselectBookSaveDir(pickerParent)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (pickerError: Throwable) {
                // 选目录真实失败 (不是用户取消): 用户可见提示 + 如实记一次, 并把**选择器原异常**
                // 交回原等待者 —— 不再拿旧的 InvalidBooksDirException 顶替它
                Toasters.get().toast(pickerError.message ?: "选择书籍目录失败")
                AppLog.put("选择书籍目录失败", pickerError)
                throw pickerError
            }
            // 用户取消选目录: 本次正常终结 (不是失败), 不再上抛
            if (!selected) return
            // 目录已可用: 重试本次远端更新 (真失败照旧上抛)
            AppWebDavShared.refreshWebDavBook(book)
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

    // ===== 其它设置: 直链上传配置 =====

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
     * 用户选定的字体目录可能在应用容器之外: 列举走 [withLocalDirAccess] 的平台授权
     * (用途 [DirUsage.Font]); 未授权/授权失效时跳过该目录, 只保留内置字体目录, 不弹任何界面。
     *
     * 支持 SAF content:// 目录的端自行 override。
     */
    override suspend fun scanFontItems(): List<FontItem> = withContext(IoDispatcher) {
        val fontRegex = AppPattern.fontFileRegex
        val items = arrayListOf<FontItem>()
        PreferenceProviders.get().getString(PreferKey.fontFolder)
            .takeIf { it.isNotBlank() }
            ?.let { fontFolder ->
                runCatching {
                    withLocalDirAccess(fontFolder, DirUsage.Font) { authorizedDir ->
                        scanFontDir(items, authorizedDir.toPath(), fontRegex)
                    }
                }.onFailure {
                    AppLog.put("字体目录读取失败, 本次只用内置字体目录: $fontFolder\n${it.message}")
                }
            }
        scanFontDir(items, DataStorageProviders.get().fontsDir.toPath(), fontRegex)
        items.distinctBy { it.name }.sortedBy { it.name }
    }

    /**
     * 字体导入: 逐个把选定文件复制进内置字体目录 (共用一份实现, 面板回传文档地址的安卓另 override)。
     * 失败的个数由调用方如实报出来。
     */
    override fun importPickedFontFiles(files: List<PickedFile>): List<String> =
        files.mapNotNull { picked ->
            val imported = picked.name.takeIf { importFontFileToInternalDir(picked.ref, it) }
            // 物化副本只在读字节时需要 (内容已落内置字体目录), 读完即清; 无物化概念的端为 no-op
            PlatformServiceProviders.get().files.discardPickedFile(picked.ref)
            imported
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
