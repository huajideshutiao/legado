package io.legado.app.ui.root

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Review
import io.legado.app.help.book.toggleBookshelfCore
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.route.encodeReviewListDialogPayload
import io.legado.app.utils.FlowBus
import io.legado.app.web.WebServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    override fun isWebServiceRunning(): Boolean = WebServerManager.isRun

    override val webServiceState: StateFlow<Boolean>? get() = WebServiceRunningState.flow

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
        AppNavigatorProviders.getOrNull()?.showOverlay(
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
            AppNavigatorProviders.getOrNull()?.showOverlay(
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
        AppNavigatorProviders.getOrNull()?.push(AppRoute.BookSourceEdit(""))
    }

    // ===== 书籍详情页 =====

    /** 上架/下架 (走 shared 统一核心 toggleBookshelfCore; 三端均无 Android 的删除确认弹窗)。 */
    override fun toggleBookshelf(
        book: Book,
        inBookshelf: Boolean,
        onComplete: (Boolean?) -> Unit,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
    ) {
        capabilityScope.launch {
            runCatching { book.toggleBookshelfCore(inBookshelf) }
                .onSuccess { onComplete(it) }
                .onFailure {
                    AppLog.put("书架操作失败\n${it.message}", it)
                    onComplete(false)
                }
        }
    }

    /** 段评列表 (对照原版 ReviewListDialog), 经 AppOverlay 弹 shared 实现。 */
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
        AppNavigatorProviders.getOrNull()?.showOverlay(AppOverlay.Dialog("theme_list"))
    }

    override fun showDefaultCoverGallery(isNight: Boolean) {
        AppNavigatorProviders.getOrNull()?.showOverlay(
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
}

/**
 * Web 服务运行态 (进程级单例): 原先三端各自持一份 `by lazy { MutableStateFlow }`,
 * 内容逐字相同, 合并到此处。对照 app 端 `FlowBus.withSticky(EventBus.WEB_SERVICE)` 桥接。
 */
private object WebServiceRunningState {

    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    val flow: MutableStateFlow<Boolean> by lazy {
        MutableStateFlow(WebServerManager.isRun).also { state ->
            scope.launch {
                FlowBus.with(EventBus.WEB_SERVICE).collect { state.value = WebServerManager.isRun }
            }
        }
    }
}
