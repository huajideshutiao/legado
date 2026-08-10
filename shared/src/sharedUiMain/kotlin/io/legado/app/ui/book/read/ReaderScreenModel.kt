package io.legado.app.ui.book.read

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelpShared
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.migrateTo
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.ReadBookPlatforms
import io.legado.app.model.ReadBookShared
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.read.ReaderPlatformProviders.getOrNull
import io.legado.app.ui.book.read.ReaderPlatformProviders.register
import io.legado.app.ui.book.read.page.PageSelPos
import io.legado.app.ui.book.read.page.PageSelectionState
import io.legado.app.ui.book.read.page.detectClickArea
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.FlowBus
import io.legado.app.utils.formatTimeOfDay
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isTrue
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

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

    /**
     * 音量键翻页开关 (对照 app 端 `AppConfig.volumeKeyPage`, 默认 true)。
     * ReaderRoute 的 VolumeUp/VolumeDown 翻页快捷键按此开关决定是否拦截;
     * 无音量键平台 (desktop/iOS/鸿蒙) 恒 true 即可。
     */
    val volumeKeyPage: Boolean get() = true

    /** 当前电池电量 0-100，-1 表示不显示 */
    fun getBatteryLevel(): Int

    /** 路由进入：注册平台窗口副作用（亮屏/系统栏等） */
    fun onEnter(screenModel: ReaderScreenModel) {}

    /** 路由退出：清理 [onEnter] 注册的副作用 */
    fun onExit(screenModel: ReaderScreenModel) {}

    /**
     * 屏幕超时设置变更 (对照 app 端 keepLightChange → upScreenTimeOut)。
     * ReaderRoute 订阅 [ReadBookEvents.keepLightChange] 后桥接, 平台 actual 重算常亮计时。
     */
    fun onKeepLightChange(screenModel: ReaderScreenModel) {}

    /** 长按页内文字触发选择 (对照 app 端 ReadView.CallBack.onPageLongClick) */
    fun onLongPress(screenModel: ReaderScreenModel) {}

    /**
     * 页内文字选择完成（长按选中文字后抬起）：携带选中文本与选区起点锚点（阅读页内坐标，
     * 含滚动折算），平台弹浮动文本操作菜单并跟随选区（对照旧 ReadView.CallBack.showTextActionMenu
     * → TextActionMenu 浮动菜单；app 端桥接 MainActivity 浮动菜单，桌面端回落对话框）。
     * 默认空实现。
     */
    fun onTextSelected(
        screenModel: ReaderScreenModel,
        text: String,
        anchorX: Float,
        anchorY: Float
    ) {
    }

    /**
     * 页内选区已消失（点按取消选择/翻页/重排/菜单动作后等任意路径）：平台收起浮动文本操作菜单。
     * 对照旧 ReadBookActivity.onCancelSelect → textActionMenu.dismiss：选区与菜单强绑定，
     * 选区消失时菜单必须同步关闭。由 ReaderRoute 收集 [ReadBookEvents.selectionDismissed]
     * 后桥接调用；默认空实现（无浮动菜单的平台如 desktop 对话框形态无需处理）。
     */
    fun onTextSelectionDismissed(screenModel: ReaderScreenModel) {}

    /**
     * 图片长按（命中图片列，携带长按点坐标）：平台弹图片操作菜单（对照旧
     * ContentTextView.longPress 的 ImageColumn 分支 → ReadBookActivity.onImageLongPress：
     * 查看/刷新/保存/选择目录）
     */
    fun onImageLongPress(screenModel: ReaderScreenModel, src: String, x: Float, y: Float) {}

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

    /**
     * 自动翻页面板的平台动作桥 (对照原版 AutoReadDialog 的 CallBack + 平台侧副作用)。
     * 默认 no-op: 未实现的端面板仍可调速度, 但停止/设置/语速动作降级为空。
     */
    fun autoPageStop(screenModel: ReaderScreenModel) {}

    /** 翻页动画配置 (对照原版 AutoReadDialog 设置按钮 → showPageAnimConfig) */
    fun showPageAnimConfig(screenModel: ReaderScreenModel) {}

    /** 自动翻页滑条抬手后同步 TTS 语速 (对照原版 AutoReadDialog upTtsSpeechRate) */
    fun upTtsSpeechRate(screenModel: ReaderScreenModel) {}

    /**
     * 朗读控制桥：驱动长按朗读弹出的共享面板
     * [io.legado.app.ui.book.read.config.ReadAloudDialog]。
     *
     * 返回 null 表示该端没有朗读实现，路由不弹面板（iOS/鸿蒙）。
     */
    fun readAloudControls(
        navigator: io.legado.app.ui.root.AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadAloudControls? = null
}

/**
 * 朗读控制动作集：对照 app 端 `ReadAloud` 门面 + `BaseReadAloudService` 静态态，
 * 平台无关地暴露给共享 [io.legado.app.ui.book.read.config.ReadAloudDialog]。
 *
 * 语速口径与原版 `AppConfig.ttsSpeechRate` 一致：Int 0..45，展示倍率 = (value + 5) / 10f。
 */
interface ReadAloudControls {
    /** 是否正在朗读（对照 `!BaseReadAloudService.pause`） */
    val isPlaying: Boolean

    /** 当前定时剩余分钟（对照 `BaseReadAloudService.timeMinute`，无定时时回落 `AppConfig.ttsTimer`） */
    val timerMinute: Int

    /** 当前语速 0..45（对照 `AppConfig.ttsSpeechRate`） */
    val speechRate: Int

    /** 是否跟随系统语速（对照 `AppConfig.ttsFlowSys`） */
    val followSys: Boolean

    /** 播放/暂停切换（对照 `ReadBookActivity.onClickReadAloud`） */
    fun playPause()

    fun stop()

    /** 上一章 / 下一章（对照原版 `ReadBook.moveToPrevChapter/moveToNextChapter`） */
    fun prevChapter()
    fun nextChapter()

    /** 上一句 / 下一句（对照 `ReadAloud.prevParagraph/nextParagraph`） */
    fun prevParagraph()
    fun nextParagraph()

    /** 设定定时关闭分钟数（对照 `ReadAloud.setTimer`） */
    fun setTimer(minute: Int)

    /** 设定语速并实时生效（对照 `AppConfig.ttsSpeechRate = v` + `ReadAloud.upTtsSpeechRate`） */
    fun setSpeechRate(rate: Int)

    /** 切换跟随系统语速 */
    fun setFollowSys(follow: Boolean)

    /** 打开目录 / 朗读设置 / 转到后台（对照原版 CallBack.openChapterList/设置按钮/finish） */
    fun openChapterList()
    fun openSettings()
    fun toBackstage()
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
 * @param onOpenSearch 打开全文搜索页回调（对照原版 ReadBookActivity.openSearchActivity：
 *   携带当前书/索引/结果列表，供 SearchMenuState "结果"按钮使用）
 */
class ReaderScreenModel(
    menuControllerFactory: (ReaderScreenModel) -> ReadMenuController,
    private val getBatteryLevel: () -> Int,
    layoutConfig: ReadBookViewModelShared.LayoutConfig = ReadBookViewModelShared.LayoutConfig.DEFAULT,
    private val onOpenSearch: (ReaderScreenModel, Book, String?) -> Unit = { _, _, _ -> },
) : ScreenModel {

    // 自管 scope（与 TocScreenModel 一致，异常兜底见 screenModelScope）
    private val scope = screenModelScope("阅读")

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
            // 阅读消息/内容状态变化后的视图刷新（对照原版 ReadBook.CallBack.upContent →
            // ReadBookActivity.upContent → readView.upContent：重新推导三页流，
            // 呈现"更新目录中…"/"加载数据中…"等消息/占位页）
            override fun upContent(
                relativePosition: Int,
                resetPageOffset: Boolean,
                success: (() -> Unit)?,
            ) {
                viewModel.onUpContent()
                success?.invoke()
            }

            override suspend fun upContentAwait(
                relativePosition: Int,
                resetPageOffset: Boolean,
                success: (() -> Unit)?,
            ) {
                viewModel.onUpContent()
                success?.invoke()
            }

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
        // region ReadBookEvents 订阅 (对照 app 端 ReadBookActivity.observeLiveBus 的 EventBus 观察者)
        // 朗读状态: STOP/PAUSE 时清当前页朗读 span (对照 app 端 ALOUD_STATE 观察者)
        scope.launch {
            ReadBookEvents.aloudState.collect { state ->
                if (state == Status.STOP || state == Status.PAUSE) {
                    viewModel.clearAloudSpanForCurrentPage()
                }
            }
        }
        // 媒体按钮: 按菜单可见性分流, 可见时弹朗读面板否则直切 (对照 app 端 MEDIA_BUTTON 观察者)
        scope.launch {
            ReadBookEvents.mediaButton.collect { _ ->
                if (menuState.isVisible) {
                    postDialogEvent(ReaderDialogEvent.ReadAloud)
                } else {
                    // 停自动翻页 (对照 app 端 onClickReadAloud 首步 autoPageStop), 再切换朗读
                    if (menuState.autoPage) menuState.clickAutoPage()
                    viewModel.toggleReadAloud()
                }
            }
        }
        // 朗读进度推进 (对照 app 端 TTS_PROGRESS sticky 观察者, replay=1 会在订阅时重放最后进度)
        scope.launch {
            ReadBookEvents.ttsProgress.collect { chapterStart ->
                viewModel.onTtsProgress(chapterStart)
            }
        }
        // 时间/电池刷新 (对照 app 端 TIME_CHANGED/BATTERY_CHANGED 观察者 → PageView.upTime/upBattery):
        // 平台广播经 ReadBookEvents 推送, 订阅者更新 StateFlow 驱动 tip 槽位重组
        scope.launch {
            ReadBookEvents.timeChanged.collect {
                _clockText.value = formatTimeOfDay(systemCurrentTimeMillis())
            }
        }
        // 无 ACTION_TIME_TICK 的平台 (桌面/iOS/鸿蒙) 整分兜底刷新:
        // 对齐原版 TimeBatteryReceiver 的 ACTION_TIME_TICK 整分语义 —— 首次 delay 等到下个整分,
        // 之后每整分刷新; 若从 model 创建时刻起算, 刷新点漂移会导致页眉周期性滞后最多 59 秒
        scope.launch {
            delay(60_000L - systemCurrentTimeMillis() % 60_000L)
            while (isActive) {
                _clockText.value = formatTimeOfDay(systemCurrentTimeMillis())
                // 桌面端无 BATTERY_CHANGED 广播, 电池随同一整分轮询读取 (对照原版 BATTERY_CHANGED 事件语义)
                _batteryLevel.value = getBatteryLevel()
                delay(60_000L)
            }
        }
        scope.launch {
            ReadBookEvents.batteryChanged.collect { level ->
                _batteryLevel.value = level
            }
        }
        // endregion

        // 九宫格点击区域配置校验 (对照原版 ReadBookViewModel.init → AppConfig.detectClickArea):
        // 全部 9 格均非“菜单”时强制恢复中间格为菜单 + toast, 避免无菜单入口死区
        detectClickArea()
    }

    val menuState: ReadMenuState get() = menuController.state

    /**
     * 页内文字选择状态（搜索跳转的程序化选区与手势选择共用）。
     * 由 ReaderRoute 经 [ReaderUiState.selection] 注入 ReadViewComposable（hoisting），
     * 保证搜索跳转与手势选择操作同一实例。
     */
    val selection = PageSelectionState()

    /**
     * 全文搜索态：是否正在展示搜索结果（对照原版 ReadBookActivity.isShowingSearchResult）。
     * 普通字段非 Compose 状态：只被返回键/点屏/搜索回传等事件读取，不驱动 UI。
     */
    var isShowingSearchResult = false

    /**
     * 搜索跳转设置选区期间为 true（对照原版同名字段）：高亮标记随选区分发。
     * setter 钳制与原版一致：非搜索态时恒 false。
     */
    var isSelectingSearchResult = false
        set(value) {
            field = value && isShowingSearchResult
        }

    /** 搜索菜单状态（对照原版 SearchMenu View），由 [SearchMenuOverlay] 组合消费 */
    val searchMenuState: SearchMenuStateImpl by lazy { SearchMenuStateImpl(this) }

    val currentBook: Book? get() = viewModel.book.value
    val currentChapter get() = viewModel.chapterList.value.getOrNull(viewModel.durChapterIndex.value)

    /**
     * 加载当前章节完整正文 (对照原版 ContentEditViewModel.initContent):
     * 从章节缓存读取全文 (非当前页), 经 ContentProcessor 完整处理 (includeTitle=false,
     * 正文不含章节标题, 标题由标题栏展示)。内容编辑对话框与桌面端文字选择对话框共用,
     * 替代误用当前页文本 (curTextPage) 作为整章正文的问题。
     *
     * @param reset true 时先删缓存并重新拉取 (在线书走 WebBook.getContentAwait), 再读取处理
     *   (对照原版 menu_reset 语义: delContent + 重拉 + 读新缓存)。
     */
    suspend fun loadChapterFullText(reset: Boolean = false): String? {
        val book = viewModel.book.value ?: return null
        val chapter = currentChapter ?: return null
        // 缓存删除/读取 + ContentProcessor 处理均含同步文件 IO, 必须切 IO 线程
        // (对照原版 ContentEditViewModel.initContent 在 Coroutine.async(IO) 中执行)
        return withContext(IoDispatcher) {
            if (reset) {
                BookStorageProviders.get().delContent(book, chapter)
                if (!book.isLocal) {
                    val source = viewModel.bookSource.value
                    if (source != null) {
                        WebBook.getContentAwait(source, book, chapter)
                    }
                }
            }
            val raw = BookHelpShared.getContent(book, chapter) ?: return@withContext null
            ContentProcessorProviders.get()
                .getContent(book, chapter, raw, includeTitle = false, useReplace = true)
                .toString()
        }
    }

    /** 书源登录入口是否可见 (对照原版 ReadMenu: menu_login.isVisible = hasLogin) */
    fun sourceLoginVisible(): Boolean = viewModel.bookSource.value?.hasLogin() == true

    /** 购买按钮是否可见 (对照原版 ReadMenu: menu_chapter_pay.isVisible = hasLogin && isVip && !isPay) */
    fun sourcePayVisible(): Boolean =
        viewModel.bookSource.value?.hasLogin() == true &&
            currentChapter?.isVip == true &&
            currentChapter?.isPay != true

    /** 书源变量对话框 (对照原版 ReadMenu.showSourceVariableDialog, 走平台能力) */
    fun showSourceVariableDialog() {
        val source = viewModel.bookSource.value ?: return
        PlatformCapabilityProviders.getOrNull()?.showBookSourceVariableDialog(source)
    }

    /** 书籍变量对话框 (对照原版 ReadMenu.showBookVariableDialog, 走平台能力) */
    fun showBookVariableDialog() {
        val book = viewModel.book.value ?: return
        PlatformCapabilityProviders.getOrNull()?.showBookVariableDialog(book)
    }

    /**
     * 书源下拉展开时的菜单可见性刷新 (对照原版 ReadMenu sourceMenu.show 前逐项赋 isVisible)。
     * 段评入口仅在书源配置了 reviewUrl 时显示。
     */
    fun updateSourceMenu() {
        menuState.topMenu.reviewVisible =
            viewModel.bookSource.value?.reviewRule?.reviewUrl.isNullOrBlank() == false
    }

    /**
     * 购买当前章 (对照原版 ReadBookActivity.payAction):
     * 执行书源 contentRule.payAction JS → 返回 URL 时交 [onOpenUrl] 打开支付页 (各端打开 WebView/浏览器);
     * 返回 true 时清本章内容缓存 + 刷新目录 (章节 isPay 随之更新)。
     * 确认弹窗由调用方 (ReaderRoute ChapterPay 对话框) 负责。
     */
    fun payChapter(onOpenUrl: (String) -> Unit) {
        val book = viewModel.book.value ?: return
        if (book.isLocal) return
        val chapter = currentChapter ?: return
        val source = viewModel.bookSource.value ?: return
        scope.launch {
            runCatching {
                val payAction = source.contentRule.payAction
                if (payAction.isNullOrBlank()) error("no pay action")
                // 工厂经各端注册的 AnalyzeRule 子类, 保平台 JS 扩展面 (对照原版 new AnalyzeRule(book, source))
                val analyzeRule = AnalyzeRuleFactories.create(source = source)
                analyzeRule.setBaseUrl(chapter.url)
                analyzeRule.chapter = chapter
                analyzeRule.evalJS(payAction).toString()
            }.onSuccess { result ->
                when {
                    result.isAbsUrl() -> onOpenUrl(result)
                    result.isTrue() -> {
                        // 购买成功后刷新目录 (对照原版: curTextChapter=null + delContent + loadChapterList)
                        BookStorageProviders.get().delContent(book, chapter)
                        viewModel.loadChapterList(book)
                    }
                }
            }.onFailure {
                AppLog.put("执行购买操作出错\n${it.stackTraceStr}", it, true)
            }
        }
    }

    private val _batteryLevel = MutableStateFlow(getBatteryLevel())
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _clockText = MutableStateFlow(formatTimeOfDay(systemCurrentTimeMillis()))
    val clockText: StateFlow<String> = _clockText.asStateFlow()

    // 搜索内容页的结果缓存, 返回阅读器后再次进入时免重搜
    // (对照 app 端 ReadBookActivity.viewModel.searchResultList/searchContentQuery/searchResultIndex)
    var searchResultList: List<SearchResult>? = null
    var searchContentQuery: String = ""
    var searchResultIndex: Int = 0

    // region 全文搜索态（对照原版 ReadBookActivity 的搜索段逻辑）

    /**
     * 搜索页选中结果回传后进入搜索态（对照原版 searchContentActivity 回调体
     * ReadBookActivity:170-186）：灌列表 + 置搜索态 + 存进度快照 + 跳转 + 弹搜索菜单。
     */
    fun onSearchContentResult(searchResult: SearchResult) {
        searchMenuState.upSearchResultList(searchResultList.orEmpty())
        isShowingSearchResult = true
        searchMenuState.updateSearchResultIndex(searchResultIndex)
        // 退出全文搜索恢复此时进度（原版注释原文）
        saveCurrentBookProgress()
        skipToSearch(searchResult)
        showActionMenu()
    }

    /**
     * 全文搜索跳转（对照原版 ReadBookActivity.skipToSearch）：跨章时等新章装载完成
     * 再定位（用 ReadBookShared.openChapter 的 success 回调，与 app 端 ReadBook.openChapter
     * 同语义；ReadBookViewModelShared.openChapter 的 success 是立即触发的不适用）。
     */
    fun skipToSearch(searchResult: SearchResult) {
        if (searchResult.chapterIndex != readBook.durChapterIndexValue) {
            readBook.openChapter(searchResult.chapterIndex) {
                jumpToPosition(searchResult)
            }
        } else {
            jumpToPosition(searchResult)
        }
    }

    /**
     * 跳转到命中位置并高亮（对照原版 ReadBookActivity.jumpToPosition:1140-1161）。
     * 命中高亮即文字选择机制：skipToPage 完成回调里用 [selection.selectRange] 设置选区，
     * isSelectingSearchResult 包夹期间同时标记 isSearchResult（对照旧 upSelectChars）。
     */
    private fun jumpToPosition(searchResult: SearchResult) {
        val curTextChapter = viewModel.curTextChapter.value ?: return
        searchMenuState.updateSearchInfo()
        val query = searchContentQuery
        val pos = searchResultPositions(
            pages = curTextChapter.pages,
            // 对照原版 TextChapter.getContent(): pages 拼接
            content = curTextChapter.pages.joinToString("") { it.text },
            query = query,
            searchResult = searchResult,
        )
        readBook.skipToPage(pos.pageIndex) {
            val page = viewModel.curTextPage.value ?: return@skipToPage
            // 对照旧 upSelectChars 的跨页覆盖清除：每次跳转重算前三页 selected/isSearchResult，
            // 旧页（prev/next 流）残留的高亮在此清掉（只清 searchResult 列表内列，不动手动选区）
            clearSearchResult()
            isSelectingSearchResult = true
            val start = PageSelPos(pos.lineIndex, pos.charIndex)
            val end = when (pos.addLine) {
                0 -> PageSelPos(pos.lineIndex, pos.charIndex + query.length - 1)
                1 -> PageSelPos(pos.lineIndex + 1, pos.charIndex2)
                // 跨页命中：原版 selectEndMoveIndex(1, 0, charIndex2) 终点在下一页，
                // 单页模型无法表达，降级为当前页末行末列——与原版横向翻页模式行为一致
                // （终点在下一页时当前页起点→页尾全部 selected）；滚动模式差异见交付报告
                else -> {
                    if (page.lines.isEmpty()) return@skipToPage // 占位页无行不设选区
                    PageSelPos(page.lines.lastIndex, page.lines.last().columns.lastIndex)
                }
            }
            selection.selectRange(page, start, end, markSearchResult = true)
            isSelectingSearchResult = false
        }
    }

    /**
     * 退出搜索态（对照原版 ReadBookActivity.exitSearchMenu:869-879）：
     * 置 false + 隐藏搜索菜单 + 清搜索结果高亮 + 取消选区。
     */
    fun exitSearchMenu() {
        if (!isShowingSearchResult) return
        isShowingSearchResult = false
        // 原版 searchMenu.invalidate() + invisible()（Compose 无重绘概念，只隐藏根）
        searchMenuState.hideRoot()
        // 原版 ReadBook.clearSearchResult() + readView.cancelSelect(true)
        clearSearchResult()
        selection.cancel()
    }

    /** 清搜索结果高亮（对照原版 ReadBook.clearSearchResult → TextChapter.clearSearchResult） */
    fun clearSearchResult() = readBook.clearSearchResult()

    /**
     * 打开全文搜索页（对照原版 ReadBookActivity.openSearchActivity:823-833）：
     * 搜索词取选中结果的 query，回退当前搜索词；结果列表仅在首条 query 与当前
     * 搜索词一致时携带（原版防列表与搜索词不匹配的条件）。
     */
    fun openSearchActivity(searchWord: String?) {
        val book = currentBook ?: return
        onOpenSearch(this, book, searchWord)
    }

    // endregion

    /**
     * 初始化书籍并装载章节（对照 app 端 ReadBookViewModel.initData + applyBookmarkPosition）。
     * chapterIndex + chapterPos 来自书签跳转入口（对照 app 端 intent extra chapterIndex/chapterPos）。
     *
     * 装载完成后按原版 initBook 语义补两件事：
     * - 云进度同步（仅同书 + 朗读运行中跳过）
     * - 无书源时自动换源（而非静默失败）
     */
    fun initBook(book: Book, chapterIndex: Int?, chapterPos: Int? = null) {
        val isSameBook = readBook.book.value?.bookUrl == book.bookUrl
        readBook.loadBook(book)
        // 对照原版 applyBookmarkPosition: 带跳转目标且目标位置与当前进度不同时, 先存
        // 跳转前进度快照再跳 (返回键可恢复跳转前进度; 位置相同/无定位参数的重装不触发,
        // 如模拟追读 initBook(book, book.durChapterIndex))
        if (chapterIndex != null &&
            (readBook.durChapterIndexValue != chapterIndex ||
                (chapterPos != null && readBook.durChapterPosValue != chapterPos))
        ) {
            readBook.saveCurrentBookProgress()
        }
        viewModel.loadChapter(chapterIndex ?: book.durChapterIndex)
        // 对照 app 端 applyBookmarkPosition: chapterIndex 有效时跳转到指定 chapterPos
        if (chapterIndex != null && chapterPos != null) {
            readBook.updateDurChapterPos(chapterPos)
        }
        // 对照原版 initBook: 打开书即同步云进度 (原版每次 initBook 都 syncProgress,
        // 仅同书 + 朗读运行中跳过; 书签跳转等入口同样触发, 与原版 chapterChanged 之外的行为一致)
        viewModel.syncProgressOnBookOpen(book, isSameBook)
        // 对照原版 initBook: 非本地书且无书源时自动换源, 不再静默失败
        if (!book.isLocal && readBook.bookSource.value == null) {
            autoChangeSource(book.name, book.author)
        }
    }

    /**
     * 自动换源 (对照原版 BaseReadViewModel.autoChangeSource):
     * 遍历启用文本书源, 并发精确搜索 + 取目录 + 预取首章正文, 首个成功源直接换源落地。
     * 全部失败则记日志 + toast (原版 catch 语义)。
     */
    private fun autoChangeSource(name: String, author: String) {
        // 对照原版 `if (!AppConfig.autoChangeSource) return` (默认 true)
        if (!PreferenceProviders.get().getBoolean(PreferKey.autoChangeSource, true)) return
        scope.launch {
            // 对照原版 getTextEnabledSources() = appDb.bookSourceDao.allTextEnabledPart
            val sources = AppDbProviders.get().bookSourceDao.allTextEnabledPart()
            flow {
                for (source in sources) {
                    AppDbProviders.get().bookSourceDao.getBookSource(source.bookSourceUrl)?.let {
                        emit(it)
                    }
                }
            }.onStart {
                    // 对照原版 onSourceChanging(R.string.source_auto_changing)
                    readBook.upMsg("自动换源中…")
                }.mapParallelSafe(AppConfigProviders.get().threadCount, sources.size) { source ->
                    val book = WebBook.preciseSearchAwait(source, name, author).getOrThrow()
                    if (book.tocUrl.isEmpty()) {
                        WebBook.getBookInfoAwait(source, book)
                    }
                    val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
                    val chapter = toc.getOrElse(book.durChapterIndex) { toc.last() }
                    val nextChapter = toc.getOrElse(chapter.index + 1) { toc.first() }
                    WebBook.getContentAwait(
                        bookSource = source,
                        book = book,
                        bookChapter = chapter,
                        nextChapterUrl = nextChapter.url
                    )
                    Triple(book, toc, source)
                }.take(1).onEach { (book, toc, source) ->
                    // 对照原版 changeTo(curBookSource!!, book, toc): 新书源即搜索命中的 source,
                    // 落地语义同 [changeTo] (迁移+落库+重装)
                    changeTo(source, book, toc)
                }.onEmpty {
                    throw NoStackTraceException("没有合适书源")
                }.onCompletion {
                    readBook.upMsg(null)
                }.catch {
                AppLog.put("自动换源失败\n${it.message}", it)
                Toasters.get().toast("自动换源失败\n${it.message}")
                }.collect()
        }
    }

    /** 刷新电池电量（平台收到电量变化广播时调用） */
    fun refreshBattery() {
        _batteryLevel.value = getBatteryLevel()
    }

    /**
     * 整书换源落地（对照 app 端 `BaseReadViewModel.changeTo`）。
     *
     * 换源页只负责搜出 (source, newBook, toc)，迁移与落库必须在此完成：
     * 迁移进度/分组等字段 → 删旧书 → 插新书 → 插新目录，最后重新装载。
     * 少任一步都会导致书架残留旧书或目录取不到（新目录只存在于内存）。
     */
    fun changeTo(source: BookSource, newBook: Book, toc: List<BookChapter>) {
        scope.launch {
            runCatching {
                val oldBook = currentBook
                oldBook?.migrateTo(newBook, toc)
                // 未入书架的书原版同样不落库 (BaseReadViewModel.changeTo 的 inBookshelf 守卫,
                // 此处沿用 ReadBookViewModelShared.loadChapterListFromSource 的 isNotShelf 口径)
                if (oldBook != null && !oldBook.isNotShelf) {
                    newBook.removeType(BookType.updateError)
                    AppDbProviders.get().bookDao.delete(oldBook)
                    AppDbProviders.get().bookDao.insert(newBook)
                    AppDbProviders.get().bookChapterDao.insert(*toc.toTypedArray())
                }
                readBook.loadBook(newBook)
                readBook.bookSourceValue = source
                // 对照原版 chapterListData.postValue(toc): 目录先进内存,
                // 未入书架的书没落库, 少了这步会再回源拉一次目录
                readBook.updateChapterList(toc)
                // 对照 onSourceChanged: ReadBook.initData(book) + loadContent
                viewModel.loadChapter(newBook.durChapterIndex)
            }.onFailure {
                AppLog.put("换源失败\n$it", it, true)
            }
            FlowBus.with(EventBus.SOURCE_CHANGED).tryEmit(newBook.bookUrl)
        }
    }

    // 恢复跳转前进度对话框的交互结果 (对照原版 ReadBookActivity.confirmRestoreProcess:
    // Activity 字段, null=未表态 / true=总是恢复 / false=不再恢复; 不持久化, 退出阅读页
    // ScreenModel 销毁重建时自然重置, 换书不重置与原版一致)
    var confirmRestoreProcess: Boolean? = null

    /** 跳转前进度快照 (对照原版 ReadBook.lastBookProgress) */
    val lastBookProgress: BookProgress? get() = readBook.lastBookProgress

    /** 存跳转前进度快照 (对照原版 ReadBook.saveCurrentBookProgress, 已有快照时不覆盖) */
    fun saveCurrentBookProgress() = readBook.saveCurrentBookProgress()

    /** 恢复并清空快照 (对照原版 ReadBook.restoreLastBookProgress) */
    fun restoreLastBookProgress() = readBook.restoreLastBookProgress()

    /** 放弃快照 (对照原版 restoreLastBookProcess 的 noButton/onCancelled 分支) */
    fun clearLastBookProgress() {
        readBook.lastBookProgress = null
    }

    /**
     * 返回键恢复跳转前进度入口 (对照原版 ReadBookActivity.restoreLastBookProcess 三态):
     * true=总是恢复直接恢复; null=未表态弹确认框; false=不再恢复无动作 (返回键进入条件已排除)。
     */
    fun restoreLastBookProcess() {
        when {
            confirmRestoreProcess == true -> readBook.restoreLastBookProgress()
            confirmRestoreProcess == null ->
                postDialogEvent(ReaderDialogEvent.RestoreProcessConfirm)
        }
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

    /** 显示阅读菜单（对照 app 端 ReadBookActivity.showActionMenu:749-755 的四段顺序：
     *  朗读运行中 → 朗读面板；自动翻页 → AutoRead 面板；搜索态 → 搜索菜单；否则常规菜单） */
    fun showMenu() {
        when {
            ReadBookPlatforms.get().isReadAloudRun ->
                postDialogEvent(ReaderDialogEvent.ReadAloud)

            menuState.autoPage -> postDialogEvent(ReaderDialogEvent.AutoRead)
            isShowingSearchResult -> searchMenuState.runMenuIn()
            else -> menuController.showMenu()
        }
    }

    /** 弹菜单（对照原版 showActionMenu，含搜索态分支；供搜索页回传与其它入口复用） */
    fun showActionMenu() = showMenu()

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
        // 对照原版 ReadBookActivity.onDestroy: 立即结束阅读计时 (不等待 end 的延迟结算)
        ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.READ_BOOK)
        viewModel.onCleared()
        scope.cancel()
    }

    /**
     * 平台 onPause 入口（对照 app 端 ReadBookActivity.onPause）。
     *
     * 平台 actual 在 Activity Lifecycle ON_PAUSE 时调 [ReaderPlatformProvider.onPause]，
     * 由其桥接到本方法。完成：
     * - 落库并上传当前阅读进度（对照原版 onPause 的 `ReadBook.saveRead()` + `uploadProgress()`，
     *   [ReadBookViewModelShared.uploadProgress] 内部先落库再按配置上传，走独立 progressSyncScope）
     * - 取消预下载任务（对照原版 `ReadBook.cancelPreDownloadTask()`）
     *
     * 退出阅读（DisposableEffect.onDispose → [ReadBookViewModelShared.onCleared]）时同样落库上传。
     */
    fun onPause() {
        // 对照原版 ReadBookActivity.onPause: 结束阅读计时
        ReadTimeRecorder.end(ReadTimeRecorder.Source.READ_BOOK)
        viewModel.uploadProgress()
        viewModel.cancelPreDownloadTask()
    }

    /**
     * 平台 onResume 入口（对照 app 端 ReadBookActivity.onResume）。
     *
     * - 开始阅读计时 (原版 onResume 首行 ReadTimeRecorder.start(READ_BOOK))
     * - web 端阅读时, app 处于阅读界面, 本地记录会覆盖 web 保存的进度, 在此处恢复
     *   (原版 onResume else 分支: webBookProgress?.let { setProgress; 置 null })
     *
     * 网络监听/广播注册/时间刷新等由平台 actual 接入。
     */
    fun onResume() {
        ReadTimeRecorder.start(
            ReadTimeRecorder.Source.READ_BOOK,
            readBook.book.value?.name ?: ""
        )
        readBook.webBookProgressValue?.let {
            readBook.setProgress(it)
            readBook.updateWebBookProgress(null)
        }
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

    /** 朗读控制面板 (对照原版 ReadMenu 朗读按钮长按 → showReadAloudDialog) */
    object ReadAloud : ReaderDialogEvent

    /** 更多设置 (对照原版 设置按钮 → MoreConfigDialog) */
    object MoreConfig : ReaderDialogEvent

    /** 界面/样式设置 (对照原版 界面按钮 → ReadStyleDialog) */
    object ReadStyle : ReaderDialogEvent

    /** 起效的替换规则 (对照原版 替换按钮 → EffectiveReplacesDialog) */
    object EffectiveReplaces : ReaderDialogEvent

    /** 朗读设置 (对照原版 朗读面板设置按钮 → ReadAloudConfigDialog) */
    object ReadAloudConfig : ReaderDialogEvent

    /** 编辑 HTTP TTS (对照原版 SpeakEngineDialog 中 "+" 按钮 → HttpTtsEditDialog, 默认新增) */
    data object HttpTtsEdit : ReaderDialogEvent

    /** 选择朗读引擎 (对照原版 朗读面板选择引擎 → SpeakEngineDialog) */
    data object SpeakEngine : ReaderDialogEvent

    /** 翻页键配置 (对照原版 更多设置 → PageKeyDialog) */
    data object PageKey : ReaderDialogEvent

    /** 章节购买确认 (对照原版 ReadBookActivity.payAction 的 alert 确认, 确认后执行 payAction JS) */
    data object ChapterPay : ReaderDialogEvent

    /** 返回键恢复跳转前进度确认 (对照原版 restoreLastBookProcess 的 alert:
     *  是=恢复并以后总是恢复, 否/关闭=放弃快照并以后不再询问) */
    data object RestoreProcessConfirm : ReaderDialogEvent

    /** 未入架书退出确认 (对照原版 BaseReadActivity.finish 的"加入书架"弹窗, 确定=入架保留进度, 取消=删除) */
    data object AddToShelfConfirm : ReaderDialogEvent

    /** 目录 (对照原版 目录按钮 → TocDialog, 全高底部弹窗) */
    object Toc : ReaderDialogEvent

    /** 整书换源 (对照原版 换源按钮 → ChangeBookSourceDialog, 全高底部弹窗) */
    object ChangeSource : ReaderDialogEvent

    /** 自动翻页控制面板 (对照原版 自动翻页运行时点屏幕 → AutoReadDialog: 速度滑条面板) */
    object AutoRead : ReaderDialogEvent

    /** 章节换源 (对照原版 换源图标长按 → ChangeChapterSourceDialog, 全高底部弹窗) */
    object ChangeChapterSource : ReaderDialogEvent

    // ===== 溢出菜单选择器 (iOS/鸿蒙/desktop 共用, 对照 app 端 ReadMenu 的 selector/alert 弹窗) =====

    /** 模拟追读配置 (对照原版 menu_simulated_reading → showSimulatedReading) */
    data object SimulatedReading : ReaderDialogEvent

    /** 图片样式 4 项选择器 (对照原版 menu_image_style) */
    data object ImageStyle : ReaderDialogEvent

    /** 离线缓存起止章节 (对照原版 menu_download → showDownloadDialog) */
    data object Download : ReaderDialogEvent

    /** 文本编码选择器 (对照原版 menu_set_charset → showCharsetConfig) */
    data object SetCharset : ReaderDialogEvent
}
