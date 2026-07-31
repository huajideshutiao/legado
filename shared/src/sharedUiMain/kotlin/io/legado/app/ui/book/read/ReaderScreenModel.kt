package io.legado.app.ui.book.read

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.ReadBookShared
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 阅读页平台能力注入接口。
 *
 * [ReadMenuState] 实现深度依赖平台宿主（app 端 [ReadMenu] 依赖 Activity/lifecycleScope/
 * ReadBookConfig/ThemeConfig 等），无法在 shared 层直接创建。各平台 actual 实现本接口
 * 并通过 [ReaderPlatformProviders.register] 注册。
 *
 * 对照 [io.legado.app.ui.root.PlatformServiceProviders] 的注册模式。
 */
interface ReaderPlatformProvider {
    /** 创建平台菜单控制器（含 [ReadMenuState] + 显隐触发） */
    fun createMenuController(
        navigator: io.legado.app.ui.root.AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController

    /** 当前电池电量 0-100，-1 表示不显示 */
    fun getBatteryLevel(): Int

    /** 路由进入：注册平台窗口副作用（亮屏/系统栏等） */
    fun onEnter(screenModel: ReaderScreenModel) {}

    /** 路由退出：清理 [onEnter] 注册的副作用 */
    fun onExit(screenModel: ReaderScreenModel) {}

    /** 长按页内文字触发选择 (对照 app 端 ReadView.CallBack.onPageLongClick) */
    fun onLongPress(screenModel: ReaderScreenModel) {}

    /**
     * 平台宿主 onPause（对照 app 端 ReadBookActivity.onPause）：默认空实现，
     * 平台 actual 在 Activity Lifecycle 中调用 [ReaderScreenModel.onPause]。
     */
    fun onPause(screenModel: ReaderScreenModel) {}

    /**
     * 平台宿主 onResume（对照 app 端 ReadBookActivity.onResume）：默认空实现，
     * 平台 actual 在 Activity Lifecycle 中调用 [ReaderScreenModel.onResume]。
     */
    fun onResume(screenModel: ReaderScreenModel) {}
}

/**
 * 阅读菜单控制器：封装 [ReadMenuState] + 显隐触发。
 *
 * app 端 [ReadMenu.runMenuIn]/[ReadMenu.runMenuOut] 含平台专属副作用
 * （sourceActionText 赋值、onMenuShow/onMenuHide 回调、系统栏刷新等），
 * 不适合直接下沉到 shared，故由平台实现本接口桥接。
 *
 * app 端实现示例：
 * ```kotlin
 * class AppReadMenuController(readMenu: ReadMenu) : ReadMenuController {
 *     override val state: ReadMenuState get() = readMenu
 *     override fun showMenu() = readMenu.runMenuIn()
 *     override fun hideMenu() = readMenu.runMenuOut()
 * }
 * ```
 */
interface ReadMenuController {
    val state: ReadMenuState
    fun showMenu()
    fun hideMenu()
}

/**
 * 阅读页平台能力注册中心。
 *
 * 平台入口在系统启动时调用 [register]；shared 路由层通过 [getOrNull] 取用，
 * 未注册属于启动接线错误，由路由显式失败，避免展示无反馈空页。
 */
object ReaderPlatformProviders {
    @Volatile
    private var impl: ReaderPlatformProvider? = null

    fun register(provider: ReaderPlatformProvider) {
        impl = provider
    }

    fun getOrNull(): ReaderPlatformProvider? = impl
}

/**
 * 阅读页 shared ScreenModel：封装 [ReadBookViewModelShared] 的创建与生命周期，
 * 持有 [ReadMenuController] 桥接平台菜单。
 *
 * 对照 app 端 [ReadBookActivity]：
 * - [viewModel] 创建：app 端由 ReadBookViewModel 持有，shared 端在本类构造
 * - [menuController]：app 端 readMenu 字段，shared 端通过 [ReaderPlatformProvider] 注入
 * - [batteryLevel]：app 端 TimeBatteryReceiver 广播，shared 端由 provider 提供 + [refreshBattery] 刷新
 *
 * 生命周期：[ScreenModelStore.retain] 移除本条目时调 [onCleared]，
 * 触发 [ReadBookViewModelShared.onCleared] 落库 + 上传进度（对照 app onPause）。
 *
 * @param menuController 平台注入的菜单控制器
 * @param getBatteryLevel 电池电量获取函数（平台定时调 [refreshBattery] 推送新值）
 * @param layoutConfig 排版配置，默认 [ReadBookViewModelShared.LayoutConfig.DEFAULT]
 */
class ReaderScreenModel(
    menuControllerFactory: (ReaderScreenModel) -> ReadMenuController,
    private val getBatteryLevel: () -> Int,
    layoutConfig: ReadBookViewModelShared.LayoutConfig = ReadBookViewModelShared.LayoutConfig.DEFAULT,
) : ScreenModel {

    // 自管 scope（与 TocScreenModel 一致：SupervisorJob + Dispatchers.Default）
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val readBook = ReadBookShared()
    val menuController: ReadMenuController by lazy { menuControllerFactory(this) }

    val viewModel: ReadBookViewModelShared = ReadBookViewModelShared(
        readBook = readBook,
        scope = scope,
        layoutConfig = layoutConfig,
    )

    init {
        ActiveReadBookRegistry.attach(readBook)
        // 桥接 ReadBookShared 回调到 ReadBookEvents (对照 app 端 ReadBook.CallBack → Activity 方法)
        readBook.callback = object : ReadBookShared.ReadBookCallback {
            override fun onBookChanged(book: Book) = ReadBookEvents.postMenuRefresh()

            override fun onChapterChanged(index: Int) {
                ReadBookEvents.postSeekBarChange()
                ReadBookEvents.postMenuRefresh()
            }

            override fun onPageChanged() = ReadBookEvents.postSeekBarChange()

            override fun onChapterListChanged(chapterList: List<BookChapter>) =
                ReadBookEvents.postMenuRefresh()

            override fun onBookContentChanged() {
                ReadBookEvents.postSeekBarChange()
                ReadBookEvents.postMenuRefresh()
            }
        }
    }

    val menuState: ReadMenuState get() = menuController.state
    val currentBook: Book? get() = viewModel.book.value
    val currentChapter get() = viewModel.chapterList.value.getOrNull(viewModel.durChapterIndex.value)
    val currentChapterText: String
        get() = viewModel.curTextPage.value?.lines?.joinToString("\n") { line ->
            line.columns.filterIsInstance<io.legado.app.ui.book.read.page.entities.column.TextColumn>()
                .joinToString("") { column -> column.charData }
        }.orEmpty()

    private val _batteryLevel = MutableStateFlow(getBatteryLevel())
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    /**
     * 初始化书籍并装载章节（对照 app 端 ReadBookViewModel.initData + applyBookmarkPosition）。
     * chapterIndex + chapterPos 来自书签跳转入口（对照 app 端 intent extra chapterIndex/chapterPos）。
     */
    fun initBook(book: Book, chapterIndex: Int?, chapterPos: Int? = null) {
        readBook.loadBook(book)
        viewModel.loadChapter(chapterIndex ?: book.durChapterIndex)
        // 对照 app 端 applyBookmarkPosition: chapterIndex 有效时跳转到指定 chapterPos
        if (chapterIndex != null && chapterPos != null) {
            readBook.updateDurChapterPos(chapterPos)
        }
    }

    /** 刷新电池电量（平台收到电量变化广播时调用） */
    fun refreshBattery() {
        _batteryLevel.value = getBatteryLevel()
    }

    // region 对话框事件 (书签/正文编辑/日志, 由 AndroidReaderMenuState 触发, ReaderRoute 渲染)
    private val _dialogEvent = MutableStateFlow<ReaderDialogEvent?>(null)
    val dialogEvent: StateFlow<ReaderDialogEvent?> = _dialogEvent.asStateFlow()

    fun postDialogEvent(event: ReaderDialogEvent) {
        _dialogEvent.value = event
    }

    fun clearDialogEvent() {
        _dialogEvent.value = null
    }
    // endregion

    /** 显示阅读菜单（对照 app 端 ReadBookActivity.showMenuBar → readMenu.runMenuIn） */
    fun showMenu() {
        menuController.showMenu()
    }

    /**
     * 跳转到指定章节位置 (对照 app 端 ReadBook.openChapter + applyBookmarkPosition)。
     * 供 Toc/书签结果回传后调用。
     */
    fun openChapter(index: Int, pos: Int? = null) {
        viewModel.loadChapter(index)
        if (pos != null) {
            readBook.updateDurChapterPos(pos)
        }
    }

    override fun onCleared() {
        ActiveReadBookRegistry.detach(readBook)
        viewModel.onCleared()
        scope.cancel()
    }

    /**
     * 平台 onPause 入口（对照 app 端 ReadBookActivity.onPause）。
     *
     * 平台 actual 在 Activity Lifecycle ON_PAUSE 时调 [ReaderPlatformProvider.onPause]，
     * 由其桥接到本方法。完成：
     * - 落库当前阅读进度（防止 Activity 被销毁时丢进度，对照原版 `ReadBook.saveRead()`）
     * - 取消预下载任务（对照原版 `ReadBook.cancelPreDownloadTask()`）
     *
     * 上传进度交由 [ReadBookViewModelShared.onCleared]（ DisposableEffect.onDispose 触发）
     * 与切章时的 `uploadProgress()`，与原版 onPause 一致。
     */
    fun onPause() {
        viewModel.saveProgress()
        viewModel.cancelPreDownloadTask()
    }

    /**
     * 平台 onResume 入口（对照 app 端 ReadBookActivity.onResume）。
     *
     * shared 端无 onResume 等价副作用（网络监听/广播注册/时间刷新等由平台 actual 接入）；
     * 预留扩展点供未来下沉云进度同步等逻辑。
     */
    fun onResume() {
        // 占位：当前 shared 端无 onResume 等价副作用
    }
}

/** 阅读页对话框事件 (由平台菜单状态触发, Route 层渲染对应 shared Composable 对话框) */
sealed interface ReaderDialogEvent {
    /** 添加书签 (携带预填充的 Bookmark) */
    data class AddBookmark(val bookmark: Bookmark) : ReaderDialogEvent

    /** 编辑正文 */
    object EditContent : ReaderDialogEvent

    /** 查看日志 */
    object Log : ReaderDialogEvent
}
