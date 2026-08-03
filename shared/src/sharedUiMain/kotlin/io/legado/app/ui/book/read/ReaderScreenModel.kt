package io.legado.app.ui.book.read

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.migrateTo
import io.legado.app.help.book.removeType
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.ReadBookShared
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.ui.book.read.ReaderPlatformProviders.getOrNull
import io.legado.app.ui.book.read.ReaderPlatformProviders.register
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.ScreenModel
import io.legado.app.utils.FlowBus
import io.legado.app.utils.formatTimeOfDay
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isTrue
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
     * 页内文字选择完成（长按选中文字后抬起）：携带选中文本，平台弹选择菜单
     * （对照旧 ReadView.CallBack.showTextActionMenu；app 端桥接到 TextSelectionDialog
     * 并注入选中文本，见 MainActivity.showReaderTextSelection）。默认空实现。
     */
    fun onTextSelected(screenModel: ReaderScreenModel, text: String) {}

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
        // 无 ACTION_TIME_TICK 的平台 (桌面/iOS/鸿蒙) 每分钟兜底刷新 (对照 MangaReaderScreenModel 轮询)
        scope.launch {
            while (isActive) {
                delay(60_000L)
                _clockText.value = formatTimeOfDay(systemCurrentTimeMillis())
            }
        }
        scope.launch {
            ReadBookEvents.batteryChanged.collect { level ->
                _batteryLevel.value = level
            }
        }
        // endregion
    }

    val menuState: ReadMenuState get() = menuController.state
    val currentBook: Book? get() = viewModel.book.value
    val currentChapter get() = viewModel.chapterList.value.getOrNull(viewModel.durChapterIndex.value)

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
    val currentChapterText: String
        get() = viewModel.curTextPage.value?.lines?.joinToString("\n") { line ->
            line.columns.filterIsInstance<io.legado.app.ui.book.read.page.entities.column.TextColumn>()
                .joinToString("") { column -> column.charData }
        }.orEmpty()

    private val _batteryLevel = MutableStateFlow(getBatteryLevel())
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _clockText = MutableStateFlow(formatTimeOfDay(systemCurrentTimeMillis()))
    val clockText: StateFlow<String> = _clockText.asStateFlow()

    // 搜索内容页的结果缓存, 返回阅读器后再次进入时免重搜
    // (对照 app 端 ReadBookActivity.viewModel.searchResultList/searchContentQuery/searchResultIndex)
    var searchResultList: List<SearchResult>? = null
    var searchContentQuery: String = ""
    var searchResultIndex: Int = 0

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
     * - 落库并上传当前阅读进度（对照原版 onPause 的 `ReadBook.saveRead()` + `uploadProgress()`，
     *   [ReadBookViewModelShared.uploadProgress] 内部先落库再按配置上传，走独立 progressSyncScope）
     * - 取消预下载任务（对照原版 `ReadBook.cancelPreDownloadTask()`）
     *
     * 退出阅读（DisposableEffect.onDispose → [ReadBookViewModelShared.onCleared]）时同样落库上传。
     */
    fun onPause() {
        viewModel.uploadProgress()
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

    /** 目录 (对照原版 目录按钮 → TocDialog, 全高底部弹窗) */
    object Toc : ReaderDialogEvent

    /** 整书换源 (对照原版 换源按钮 → ChangeBookSourceDialog, 全高底部弹窗) */
    object ChangeSource : ReaderDialogEvent

    /** 章节换源 (对照原版 换源图标长按 → ChangeChapterSourceDialog, 全高底部弹窗) */
    object ChangeChapterSource : ReaderDialogEvent

    // ===== 溢出菜单选择器 (iOS/鸿蒙/desktop 共用, 对照 app 端 ReadMenu 的 selector/alert 弹窗) =====

    /** 模拟阅读配置 (对照原版 menu_simulated_reading → showSimulatedReading) */
    data object SimulatedReading : ReaderDialogEvent

    /** 图片样式 4 项选择器 (对照原版 menu_image_style) */
    data object ImageStyle : ReaderDialogEvent

    /** 离线缓存起止章节 (对照原版 menu_download → showDownloadDialog) */
    data object Download : ReaderDialogEvent

    /** 文本编码选择器 (对照原版 menu_set_charset → showCharsetConfig) */
    data object SetCharset : ReaderDialogEvent
}
