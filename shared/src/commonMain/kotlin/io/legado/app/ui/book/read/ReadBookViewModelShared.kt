package io.legado.app.ui.book.read

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookChapterLoader
import io.legado.app.help.book.BookHelpShared
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.addType
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.toggleBookshelfCore
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.help.tts.ReadAloudChapterUpdate
import io.legado.app.help.tts.ReadAloudPosition
import io.legado.app.model.ActiveReadAloudHostPorts
import io.legado.app.model.ActiveReadAloudOwner
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.CacheBookShared
import io.legado.app.model.ReadBookPlatforms
import io.legado.app.model.ReadBookShared
import io.legado.app.model.chapter.ChapterLoadingGuard
import io.legado.app.model.chapter.ChapterPreDownloader
import io.legado.app.model.chapter.ChapterProgressStore
import io.legado.app.model.chapter.ChapterTocUpdater
import io.legado.app.model.chapter.ChapterWindowSlot
import io.legado.app.model.chapter.chapterWindowIndices
import io.legado.app.model.chapter.chapterWindowSlotOf
import io.legado.app.model.chapter.isInChapterWindow
import io.legado.app.model.chapter.resolveChapter
import io.legado.app.model.fileBook.FileBookProviders
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.read.ReadBookViewModelShared.LayoutConfig.Companion.DEFAULT
import io.legado.app.ui.book.read.page.PageDelegateShared
import io.legado.app.ui.book.read.page.ReaderFontWeights
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.entities.tryPatchReviewCounts
import io.legado.app.ui.book.read.page.overlay.TTSHighlightOverlay
import io.legado.app.ui.book.read.page.provider.ChapterContentParserShared
import io.legado.app.ui.book.read.page.provider.ImageResolver
import io.legado.app.ui.book.read.page.provider.ImageResolverProviders
import io.legado.app.ui.book.read.page.provider.ParagraphLayoutCache
import io.legado.app.ui.book.read.page.provider.ParsedParagraph
import io.legado.app.ui.book.read.page.provider.SimpleChapterLayout
import io.legado.app.ui.book.read.page.provider.SimpleTextMeasurer
import io.legado.app.ui.book.read.page.provider.TextMeasurerProviders
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.root.screenModelScope
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * KMP 版阅读 ViewModel：用 Compose 状态流替代 app 端 `ReadBookActivity` 持有的
 * `ReadBook` + `TextPageFactory` + `ChapterProvider` 编排链路。
 *
 * 与 app 端 `ReadBook` 单例 / `TextPageFactory` 的对应：
 * - [curTextPage] / [prevTextPage] / [nextTextPage] 对应 app 端
 *   `pageFactory.curPage/prevPage/nextPage`，KMP 版改为 `StateFlow<TextPage?>` 适配 Compose 重组。
 * - [loadChapter] / [nextPage] / [prevPage] 对应 app 端 `ReadBook.loadContent` / `moveToNextPage` /
 *   `moveToPrevPage`：正文经 ContentProcessor 完整处理链后由 [SimpleChapterLayout] 排版，
 *   三章滑窗（prev/cur/next TextChapterShared）与 durChapterPos 位移均已按原版语义下沉。
 * - 用 [AppDbProviders.get].bookChapterDao 读章节列表，与 app 端 `appDb.bookChapterDao` 等价。
 * - 用 [BookStorageProviders.get].getContent 读本地章节缓存正文，与 app 端
 *   `BookHelp.getContent` 等价；桌面端需在 Main.kt 注册 `JvmBookStorage`。
 *
 * 持有 [pageDelegate] 引用：[PageDelegateShared] 接口（commonMain 平台无关 API），
 * 实例由 sharedUiMain 的 `rememberPageDelegate` 按 `ReadBookConfig.pageAnim` 创建，
 * 覆盖 app 端全部五种翻页模式。
 *
 * @param readBook 跨平台阅读状态承载类（已下沉 commonMain）
 * @param scope 协程作用域，actual 平台注入（Android=viewModelScope / 桌面=应用主作用域）
 * @param layoutConfig 排版几何 / 字号配置初值；UI 层取到真实窗口尺寸 / ReadBookConfig 后
 *   调 [updateLayoutConfig] 覆盖，默认 [LayoutConfig.DEFAULT] 仅作无注入时的兜底
 */

/** 高度-only 排版变化去抖窗口（ms），对照原版 ChapterProvider.upViewSize 的 postDelayed(300)。 */
private const val LAYOUT_HEIGHT_DEBOUNCE_MS = 300L

class ReadBookViewModelShared(
    private val readBook: ReadBookShared,
    private val scope: CoroutineScope,
    layoutConfig: LayoutConfig = LayoutConfig.DEFAULT,
) {
    /** 排版配置：窗口尺寸 / 阅读配置变化时由 UI 层调 [updateLayoutConfig] 推新值。 */
    private val _layoutConfig = MutableStateFlow(layoutConfig)
    val layoutConfig: StateFlow<LayoutConfig> = _layoutConfig.asStateFlow()

    // region 页面状态流：外部只读 StateFlow，适配 Compose 重组
    private val _curTextPage = MutableStateFlow<TextPage?>(null)
    val curTextPage: StateFlow<TextPage?> = _curTextPage.asStateFlow()

    private val _prevTextPage = MutableStateFlow<TextPage?>(null)
    val prevTextPage: StateFlow<TextPage?> = _prevTextPage.asStateFlow()

    private val _nextTextPage = MutableStateFlow<TextPage?>(null)
    val nextTextPage: StateFlow<TextPage?> = _nextTextPage.asStateFlow()

    /**
     * 第 3 页（当前页之后的第 2 页）。对照 app 端 `TextPageFactory.nextPlusPage`：
     * 滚动模式连排时正文区可能同时容纳 3 页（当前页短 + 下一页短），原版
     * `ContentTextView.drawPage` 在 `relativeOffset(2) < visibleHeight` 时绘制第 3 页，
     * 否则章末短页 + 新章短页时视口下方会出现空白。
     */
    private val _nextPlusTextPage = MutableStateFlow<TextPage?>(null)
    val nextPlusTextPage: StateFlow<TextPage?> = _nextPlusTextPage.asStateFlow()

    /**
     * 页内容原地变更版本号（段评气泡就地补丁等对 TextPage/TextLine 的原地修改）。
     *
     * 修改不改变 data class 相等性，StateFlow 去重后不会重发，Compose 侧 Canvas
     * 不会重绘。订阅者（PageViewComposable/PageContentCanvas）消费本值强制重绘，
     * 对照 app 端 `upContent` 后的 invalidate 路径。
     */
    private val _pageContentVersion = MutableStateFlow(0)
    val pageContentVersion: StateFlow<Int> get() = _pageContentVersion

    /** 页内容原地变更后自增，触发 Compose 阅读页重绘（段评气泡就地补丁等）。 */
    fun bumpPageContentVersion() {
        _pageContentVersion.value++
    }

    /**
     * 朗读高亮位置（章节序号 + 章内字符位置），null = 无高亮。
     *
     * 排版产物不再持有 `TextLine.isReadAloud` 标志位：绘制期由
     * [io.legado.app.ui.book.read.page.overlay.PageOverlayProjector.projectTTS]
     * 把本区间折算成页内高亮行区间（页/章不匹配自然投空）。
     */
    private val _ttsHighlight = MutableStateFlow<TTSHighlightOverlay?>(null)
    val ttsHighlight: StateFlow<TTSHighlightOverlay?> = _ttsHighlight.asStateFlow()

    /**
     * 设置朗读高亮位置（章内字符位置，章节取当前章）。
     *
     * 平台朗读宿主（桌面 / iOS）自行推进段落进度时直调本方法，
     * 与 [onTtsProgress] 的区别是不带"朗读播放中"守卫（宿主自己已判过状态）。
     */
    fun setAloudHighlight(chapterPos: Int) {
        _ttsHighlight.value = TTSHighlightOverlay(readBook.durChapterIndex.value, chapterPos)
    }
    // endregion

    /**
     * 当前章节已排版的全部页列表（内存缓存）。
     *
     * [loadChapter] 排版完成后填充；[nextPage] / [prevPage] 翻页时从这里取相邻页。
     * 与 app 端 `TextChapter.pages` 对应。
     */
    private val pageList: MutableList<TextPage> = arrayListOf()

    /**
     * 当前页在 [pageList] 中的索引。与 app 端 `ReadBook.durPageIndex` 对应。
     */
    private var pageIndex: Int = 0

    /**
     * 翻页动画委托。
     *
     * 由 [io.legado.app.ui.book.read.page.delegate.rememberPageDelegate] 按
     * `ReadBookConfig.pageAnim` 创建并写入（对照 app 端 `ReadView.upPageAnim`），
     * 覆盖 Cover / Slide / Simulation / Scroll / NoAnim 五种模式；阅读页离开组合时置回 null。
     *
     * 这里存的是 commonMain 接口引用，供快捷键翻页等非 Compose 入口
     * （[io.legado.app.ui.book.read.page.turnPage]）复用；Compose 渲染入口在
     * [io.legado.app.ui.book.read.page.delegate.PageDelegateCompose] 上。
     *
     * 章节边界联动：[nextPage] / [prevPage] 返回 false 时由委托的 `onAnimStop`
     * 调 [moveToNextChapter] / [moveToPrevChapter] 切章。
     */
    var pageDelegate: PageDelegateShared? = null

    // region 搜索 / 初始化 / 权限状态流 (对照 app 端 ReadBookViewModel 同名字段, 用 StateFlow 替代 LiveData)
    /** 权限拒绝事件 (对照 app 端 permissionDenialLiveData, KMP 用 StateFlow 替代 LiveData) */
    private val _permissionDenial = MutableStateFlow(0)
    val permissionDenialState: StateFlow<Int> = _permissionDenial.asStateFlow()

    /** 初始化完成标志 (对照 app 端 isInitFinishFlow) */
    private val _isInitFinish = MutableStateFlow(false)
    val isInitFinishFlow: StateFlow<Boolean> = _isInitFinish.asStateFlow()
    var isInitFinish: Boolean
        get() = _isInitFinish.value
        set(value) {
            _isInitFinish.value = value
        }

    /** 内容搜索关键字 (对照 app 端 searchContentQueryFlow) */
    private val _searchContentQuery = MutableStateFlow("")
    val searchContentQueryFlow: StateFlow<String> = _searchContentQuery.asStateFlow()
    var searchContentQuery: String
        get() = _searchContentQuery.value
        set(value) {
            _searchContentQuery.value = value
        }

    /** 章内搜索结果列表 (对照 app 端 searchResultListFlow) */
    private val _searchResultList = MutableStateFlow<List<SearchResult>?>(null)
    val searchResultListFlow: StateFlow<List<SearchResult>?> = _searchResultList.asStateFlow()
    var searchResultList: List<SearchResult>?
        get() = _searchResultList.value
        set(value) {
            _searchResultList.value = value
        }

    /** 当前搜索结果索引 (对照 app 端 searchResultIndexFlow) */
    private val _searchResultIndex = MutableStateFlow(0)
    val searchResultIndexFlow: StateFlow<Int> = _searchResultIndex.asStateFlow()
    var searchResultIndex: Int
        get() = _searchResultIndex.value
        set(value) {
            _searchResultIndex.value = value
        }

    /** 权限拒绝事件入口 (供平台 actual 在文件权限异常时调用, 对照 app 端 permissionDenialLiveData.postValue) */
    fun postPermissionDenial(code: Int) {
        _permissionDenial.value = code
    }
    // endregion

    // region 预下载状态 (对照 app 端 ReadBook 同名字段)
    /**
     * 章节装载守卫 + 任务表 (四模式共用, 见 [ChapterLoadingGuard]): 同章新任务替换旧任务、
     * 切章取消窗口外任务; 附带清理窗口外未完成的段评 IO (对照 app
     * ReadBook.clearExpiredChapterLoadingJob 的 reviewCountDeferred 分支)。
     */
    private val loadGuard = ChapterLoadingGuard(
        scope = scope,
        onExpired = { _, clearAll -> clearExpiredReviewCount(clearAll) },
    )

    /**
     * 目录加载任务（单飞）。独立于 [chapterLoadingJobs]，不参与同章替换取消：
     * 未入架书目录不落库，网络拉取若被 relayout 的同章替换掐断，chapterSize 会滞留 0、
     * loadContent 越界静默 return → 永久"加载数据中"（对照原版目录 await 就绪后才 loadContent 的时序）。
     * 切书/销毁时随 scope 取消；任务内校验书未切换才更新内存目录。
     */
    private var directoryLoadingJob: Job? = null

    /** 目录拉取失败标志：失败后不自动重试（对照原版失败后需手动"更新目录"），成功/切书时清除。 */
    private var directoryLoadFailed = false

    // 替代原 @Synchronized 的 this 监视器 (kotlin.jvm.Synchronized 无 common 变体且 native 无效)
    private val syncLock = SynchronizedObject()

    /**
     * 高度-only 排版变化去抖任务（对照原版 ChapterProvider.upViewSize 对高度变化
     * postDelayed(300) 合并）。宽度变化立即应用；仅高度变化 300ms 内合并为一次重排，
     * 避免打开阅读页时系统栏 inset / 页眉页脚测量收敛造成的瞬时多次整章重排。
     */
    private var layoutDebounceJob: Job? = null
    private val downloadScope = screenModelScope("阅读预下载", IoDispatcher)

    /**
     * 正文预下载 (与漫画模式共用实现)。
     *
     * guard 传 null: 文字侧下载走 [CacheBookShared], 并发去重由其 onDownloadSet 保证;
     * 它的完成回调不经本 VM, 占了装载标记无处释放会永久堵住该章。
     */
    private val preDownloader = ChapterPreDownloader(
        scope = scope,
        downloadScope = downloadScope,
        guard = null,
        preDownloadNum = { AppConfigProviders.get().preDownloadNum },
        chapterSize = { readBook.chapterSize },
        durChapterIndex = { readBook.durChapterIndex.value },
        isLocalBook = { readBook.book.value?.isLocal == true },
        upToc = { force -> upToc(force) },
        resolveChapter = { index ->
            readBook.book.value?.let { resolveChapter(it, index, readBook.chapterList.value) }
        },
        hasContent = { chapter ->
            readBook.book.value?.let { BookStorageProviders.get().hasContent(it, chapter) } == true
        },
        // 失败计数同步自 CacheBookShared.errorDownloadMap（原版经 CacheBook 回调写 ReadBook.downloadFailChapters）
        syncFailCount = { chapter -> CacheBookShared.errorDownloadMap[chapter.primaryStr()] },
        download = { chapter, semaphore -> downloadForPreload(chapter, semaphore) },
    )

    /**
     * 预下载单章正文 (对照 app 端 ReadBook.download): 走 [CacheBookShared] 的限流下载队列,
     * 正文落盘与失败计数由其内部完成, 完成回调经 CacheBookCallbacks 到平台侧。
     */
    private fun downloadForPreload(chapter: BookChapter, semaphore: Semaphore) {
        val book = readBook.book.value ?: return
        val bookSource = readBook.bookSource.value ?: return
        val cacheBook = CacheBookShared.getOrCreate(bookSource, book)
        if (cacheBook.chapterList == null) {
            cacheBook.chapterList = readBook.chapterList.value
        }
        cacheBook.download(downloadScope, chapter, semaphore)
    }

    /** 目录自动更新 (与漫画模式共用实现)。 */
    private val tocUpdater = ChapterTocUpdater(
        scope = scope,
        onUpdated = { _, chapters ->
            readBook.updateChapterList(chapters)
            if (readBook.nextTextChapter.value == null) {
                val nextIdx = readBook.durChapterIndex.value + 1
                loadGuard.launch(nextIdx) { loadContent(nextIdx) }
            }
        },
        // 原版文字模式失败完全静默 (runCatching 无 onError 分支), 是缺陷: 目录拉不到时
        // 用户只看到 "没有下一章" 而不知原因。改为 toast + 日志 (AppLog 已写过日志,
        // 这里只负责弹提示)。不能走 upMsg: msg 是粘性状态且在 syncPageFlows 里优先于一切
        // 并覆盖三页, 而自动目录检查是后台行为 (读到倒数三章内就触发) ——
        // 一次网络抖动会把正在看的正文整页顶成错误页, 且只有手动"更新目录"/换源能消。
        // 消息页只留给用户主动发起的 [updateToc]。
        onError = { AppLog.put(appString(AppStringKey.error_load_toc), it, true) },
    )

    /** 段评数按 chapter.index 复用；与 app ReadBook.reviewCountDeferred 生命周期一致。 */
    private val reviewCountDeferred = mutableMapOf<Int, Deferred<Map<Int, Int>?>>()
    private var reviewCountBookUrl: String? = null

    // 缓存已处理的章节内容，视口变化时只重排版不重新下载/处理
    private val processedContentCache = mutableMapOf<Int, ProcessedChapterContent>()
    private var processedContentBookUrl: String? = null

    // 段落折行度量缓存（Phase 1），窗口高度/行距/段距变化时 100% 复用
    private val paragraphLayoutCache = ParagraphLayoutCache()
    // endregion

    // region readBook 状态对外暴露 (readBook 私有, 桌面端 TTS Navigator 等外部消费者通过本区域访问)
    /**
     * 当前书籍 (委托 [readBook.book])。桌面端 TTS Navigator 用其取 bookUrl 查本地缓存。
     */
    val book: StateFlow<Book?> get() = readBook.book

    /**
     * 章节列表 (委托 [readBook.chapterList])。桌面端 TTS Navigator 用其按 index 取章节。
     */
    val chapterList: StateFlow<List<BookChapter>> get() = readBook.chapterList

    /**
     * 章节总数 (委托 [readBook.chapterSize])。桌面端 TTS Navigator 用其判定章节边界。
     */
    val chapterSize: Int get() = readBook.chapterSize

    /**
     * 当前章节索引 (委托 [readBook.durChapterIndex])。
     *
     * 目录页/目录弹窗用其高亮当前章节 + 跳转后自动滚动定位。
     * 切章时 [moveToPrevChapter] / [moveToNextChapter] / [loadChapter] 会通过
     * [ReadBookShared.updateDurChapterIndex] 推送新值, Compose 自动重组刷新高亮。
     */
    val durChapterIndex: StateFlow<Int> get() = readBook.durChapterIndex

    /** 当前书源 (委托 readBook.bookSource), 供菜单栏显示源名/登录状态 */
    val bookSource: StateFlow<BookSource?> get() = readBook.bookSource

    /** 模拟章节总数 (卷/合集展开后), 供进度条 seekMax 使用 */
    val simulatedChapterSize: Int get() = readBook.simulatedChapterSize

    /** 当前章节阅读位置 (委托 readBook.durChapterPos), 供书签记录 */
    val durChapterPos: StateFlow<Int> get() = readBook.durChapterPos

    /** 当前章排版结果 (委托 readBook.curTextChapter), 供"去重"菜单读 sameTitleRemoved */
    val curTextChapter: StateFlow<TextChapterShared?> get() = readBook.curTextChapter

    /** 当前页索引 (委托 readBook.durPageIndex), 供进度条 page 模式 seekValue 使用 */
    val durPageIndex: StateFlow<Int> get() = readBook.durPageIndex

    /** 是否滚动翻页模式 (对照 app 端 `ReadBook.isScroll`), 朗读起点定位等滚动分支使用 */
    val isScrollPageAnim: Boolean get() = readBook.isScroll

    /**
     * 当前生效翻页动画 (委托 [ReadBookShared.pageAnim], 对照 app 端 `ReadBook.pageAnim()`)。
     *
     * 带单页图片样式降级: imageStyle=SINGLE 且配置为滚动时降为覆盖。翻页委托与排版
     * 双页判定均须读本属性而非 `ReadBookConfig.pageAnim` 原始值——后者不含降级,
     * 单图模式下会造出滚动委托 (与 app 端 `ReadView.upPageAnim` 取值口径不一致)。
     */
    val pageAnim: Int get() = readBook.pageAnim()

    // region 滚动模式行级偏移 (对照 app 端 ContentTextView.pageOffset)
    /**
     * 滚动翻页模式的行级滚动偏移 (px, 恒 ≤ 0)。
     *
     * 语义与 app 端 `ContentTextView.pageOffset` 一致: 可视区顶相对当前页内容顶的位置,
     * 范围 (-当前页高, 0]; 页边界 (0 / -页高) 处由 ScrollPageDelegateCompose 在滚动过程中
     * 即时翻页并把偏移折算到新页 (旧 scroll() 的 moveToPrev/moveToNext 分支)。
     * 仅滚动模式有行级滚动; 整页/非滚动模式偏移恒为 0 (只有滚动 delegate 写入本状态)。
     *
     * 与页面流同步: 切章/重排/跳页时 [resetScrollOffset] 归零 (对照旧 resetPageOffset),
     * 章内翻页 (nextPage/prevPage) 保留偏移以维持内容连续性。
     */
    private val _scrollOffset = MutableStateFlow(0)
    val scrollOffset: StateFlow<Int> = _scrollOffset.asStateFlow()

    /** 更新滚动偏移 (仅 ScrollPageDelegateCompose 手势/动画写入) */
    fun updateScrollOffset(offset: Int) {
        _scrollOffset.value = offset
    }

    /** 重置滚动偏移 (切章/重排/跳页时, 对照 app 端 resetPageOffset) */
    private fun resetScrollOffset() {
        _scrollOffset.value = 0
    }

    /**
     * 滚动模式连续滚动跨章标记: [ScrollPageDelegateCompose.applyScrollDelta] 越过章节边界
     * 切章后置位, 该章正文装载完成 ([contentLoadFinish]) 时消费。
     *
     * 目的: 滚动中越过边界进入未装载章节时显示占位页, 章节装载完成若把滚动偏移重置为 0
     * 会从章首跳变 (对照原版 moveToNextChapter → loadContent(resetPageOffset = false) 保留偏移);
     * 消费本标记时 [applyCurChapterPages] 保留偏移, 其余装载路径 (打开书/菜单跳章) 照旧归零。
     */
    private var scrollCrossingLoadPending = false

    /** 滚动跨章后置位 (ScrollPageDelegateCompose 章节边界折算时调用) */
    fun markScrollCrossingPending() {
        scrollCrossingLoadPending = true
    }

    /** 消费滚动跨章标记: 章节装载完成时读取, true = 本次装载由滚动连续跨章触发 */
    fun consumeScrollCrossingPending(): Boolean {
        val v = scrollCrossingLoadPending
        scrollCrossingLoadPending = false
        return v
    }

    /** 清除滚动跨章标记 (显式跳章/跳页/打开书时, 防止残留标记导致误保留偏移) */
    private fun clearScrollCrossingPending() {
        scrollCrossingLoadPending = false
    }
    // endregion

    /**
     * 跳转到指定页 (对照 app 端 `ReadBook.skipToPage`)。
     * 进度条 page 模式松手后按页索引跳转, 跨章时由排版层自动接续。
     */
    fun skipToPage(index: Int) {
        clearScrollCrossingPending()
        resetScrollOffset()
        readBook.skipToPage(index)
        // 对照原版 skipToPage → curPageChanged → preDownload (页位变化即刷新预下载窗口)
        preDownload()
    }

    /**
     * 更新章节标题并落库 (对照原版 ContentEditDialog.editTitle:
     * `bookChapterDao.update(chapter)` + `ReadBook.loadContent(resetPageOffset = false)`)。
     *
     * 同步替换内存目录项 (StateFlow 换新列表实例触发重组), 使菜单/目录立即显示新标题;
     * 然后按当前进度重载正文 (标题变化影响页内显示标题)。
     */
    fun renameChapter(index: Int, newTitle: String) {
        val book = readBook.book.value ?: return
        scope.launch {
            val chapterDao = AppDbProviders.get().bookChapterDao
            val chapter = chapterDao.getChapter(book.bookUrl, index) ?: return@launch
            chapter.title = newTitle
            chapterDao.update(chapter)
            // 内存目录同步替换 (data class 新实例, 不影响其他字段引用)
            readBook.chapterListValue = readBook.chapterListValue?.map { c ->
                if (c.index == index) chapter else c
            }
            ReadBookEvents.postMenuRefresh()
            loadGuard.launch(index) { loadContent(index) }
        }
    }

    /**
     * 跳到章内字符位置并刷新页面流。
     *
     * 对照 app 端 ReadBookActivity 的 TTS_PROGRESS 观察者
     * (`ReadBook.durChapterPos = chapterStart` + upContent): 朗读推进到某段时把阅读位置
     * 拉到该段, 跨页时页面流随之翻页。
     */
    fun updateReadPosition(pos: Int) {
        readBook.updateDurChapterPos(pos)
        syncPageFlows()
    }

    // endregion

    private val readAloudOwner = object : ActiveReadAloudOwner {
        override val readBook: ReadBookShared get() = this@ReadBookViewModelShared.readBook
        override val positionUpdates = readBook.durChapterPos.map {
            ReadAloudPosition(readBook.durChapterIndexValue, it)
        }
        override val chapterUpdates = readBook.curTextChapter.mapNotNull { chapter ->
            chapter ?: return@mapNotNull null
            val error = chapter.pages.firstOrNull()?.takeIf { it.isMsgPage }
                ?.lines?.firstOrNull()?.text?.let { "加载章节内容失败: $it" }
            ReadAloudChapterUpdate(chapter.chapterIndex, chapter.pages.isNotEmpty(), error)
        }

        override fun chapterText(chapterIndex: Int): String? {
            if (chapterIndex == readBook.durChapterIndexValue) {
                readBook.curTextChapter.value?.let { chapter ->
                    if (chapter.pages.isNotEmpty() && !chapter.pages.first().isMsgPage) {
                        return chapter.pages.joinToString("") { it.text }
                    }
                }
            }
            val book = readBook.bookValue ?: return null
            val chapter = readBook.chapterListValue?.getOrNull(chapterIndex) ?: return null
            return BookStorageProviders.get().getContent(book, chapter)
        }

        override fun moveToChapter(chapterIndex: Int) {
            if (readBook.durChapterIndexValue != chapterIndex) loadChapter(chapterIndex)
        }

        override fun moveToNextPage() {
            nextPage()
        }

        override fun uploadProgress() = this@ReadBookViewModelShared.uploadProgress()
    }

    init {
        ActiveReadBookRegistry.attachViewModel(this)
        ActiveReadAloudHostPorts.attach(readAloudOwner)
    }

    /**
     * 推入新排版配置并按新参数重排（对照原版 `ChapterProvider.upStyle` / `upViewSize` 后
     * 发 LOAD_CONTENT → `ReadBook.loadContent(resetPageOffset = false)`）。
     * 视口无效或配置未变时不重排。
     *
     * 宽度变化立即应用；仅高度变化去抖 300ms 合并为一次重排（对照原版
     * `ChapterProvider.upViewSize` 的 `if (width == viewWidth) postDelayed(300)` 分支）——
     * 打开阅读页时系统栏 inset / 页眉页脚测量收敛属于瞬时高度-only 跳变，合并可避免
     * 连续多次整章重排。
     */
    fun updateLayoutConfig(config: LayoutConfig) {
        if (config.visibleWidth <= 0 || config.visibleHeight <= 0) return
        val current = _layoutConfig.value
        // 尺寸抖回原值：撤销已排队的去抖重排，否则 300ms 后会用过期视口排版
        // （对照原版 ChapterProvider.upViewSize 的 removeCallbacks 分支）
        if (current == config) {
            layoutDebounceJob?.cancel()
            layoutDebounceJob = null
            return
        }
        if (config.viewWidth == current.viewWidth) {
            // 仅高度变化：300ms 去抖合并
            layoutDebounceJob?.cancel()
            layoutDebounceJob = scope.launch {
                delay(LAYOUT_HEIGHT_DEBOUNCE_MS)
                applyLayoutConfig(config)
            }
        } else {
            // 宽度变化：立即应用
            layoutDebounceJob?.cancel()
            applyLayoutConfig(config)
        }
    }

    private fun applyLayoutConfig(config: LayoutConfig) {
        // 字体路径不在 ParagraphLayoutCache 的段落 key 中，路径变化必须整表失效，
        // 避免新字体命中旧字形宽度。字重已包含在 SimpleChapterLayout 的 body/titleFontKey，
        // textBold 切换会自然写入另一组缓存项，不需要清空整张 LRU。
        if (config.textFontPath != _layoutConfig.value.textFontPath) {
            paragraphLayoutCache.clear()
        }
        _layoutConfig.value = config
        // 当前章未装载时占位/消息页按新配置重建（加载中页几何与视口一致，对照原版
        // ChapterProvider.viewWidth 变化后 format() 重排消息页）
        if (readBook.curTextChapter.value == null || readBook.msg != null) {
            syncPageFlows()
        }
        relayoutCurrentChapter()
    }

    /**
     * 三章滑窗按当前排版参数重排：durChapterPos 不动，排版完成后
     * [applyCurChapterPages] 按字符位置回到原页（对照原版 resetPageOffset=false 的保进度语义）。
     *
     * 优先复用已缓存的正文处理结果（ContentProcessor + ChapterContentParserShared），
     * 只重跑排版（SimpleChapterLayout.layout），避免窗口大小变化时重新下载/解析正文。
     */
    fun relayoutCurrentChapter() {
        if (readBook.book.value == null) return
        val index = readBook.durChapterIndex.value
        val bookUrl = readBook.book.value?.bookUrl
        // 书籍切换时清空缓存
        if (processedContentBookUrl != bookUrl) {
            processedContentCache.clear()
            processedContentBookUrl = bookUrl
        }
        // 重排窗口三章; preDownloadNum=0 时只重排当前章 (前后章不补载, 同 loadChapter 的预载口径)
        val relayoutWindow = if (neighborLoadEnabled()) {
            chapterWindowIndices(index)
        } else {
            intArrayOf(index)
        }
        for (i in relayoutWindow) {
            val cached = processedContentCache[i]
            if (cached != null) {
                // 复用已处理内容，只重排版
                loadGuard.launch(i) {
                    relayoutFromCache(i, cached)
                }
            } else {
                loadGuard.launch(i) {
                    loadContent(i)
                }
            }
        }
    }

    /**
     * 刷新当前章节: 删除缓存 → 清滑窗 → 重新装载 (对照 app 端 refreshContentDur)。
     */
    fun refreshCurrentChapter() {
        val book = readBook.book.value ?: return
        val index = readBook.durChapterIndex.value
        processedContentCache.remove(index)
        loadGuard.launch(index) {
            val chapter = resolveChapter(book, index, readBook.chapterList.value)
            if (chapter != null) {
                BookStorageProviders.get().delContent(book, chapter)
            }
            readBook.clearTextChapter()
            loadContent(index)
        }
    }

    /**
     * 打开章节（对照 app 端 `ReadBook.openChapter` + `loadContent(resetPageOffset)`）：
     * 读章节列表 / 解析书源，跳章时清三章滑窗并重置进度，随后装载当前章并异步预载前后章。
     * 正文经 ContentProcessor 完整处理链后排版，详见 [contentLoadFinish]。
     *
     * @param chapterPos 章内字符位置; null=跳章归零、同章不动。带位置的入口 (书签/云进度/朗读定位)
     *   必须由本参数传入: 装载是异步的, 调用方在前后自行写 durChapterPos 都会被跳章分支清零
     * @param keepScrollOffset true=装载完成后保留滚动偏移 (对照原版 loadContent(resetPageOffset=false),
     *   用于编辑保存/重置后刷新阅读器: 置位滚动跨章标记, applyCurChapterPages 以 resetOffset=false
     *   归位, 滚动模式不回到章首; 其余显式装载路径默认 false 归零)
     */
    fun loadChapter(index: Int, chapterPos: Int? = null, keepScrollOffset: Boolean = false) {
        // 显式装载（打开书/菜单跳章）清零滚动跨章标记，防止残留标记导致后续装载误保留偏移;
        // keepScrollOffset=true 时反向置位, 使本次装载排版完成保留滚动偏移
        if (keepScrollOffset) {
            markScrollCrossingPending()
        } else {
            clearScrollCrossingPending()
        }
        val currentBookUrl = readBook.book.value?.bookUrl
        if (reviewCountBookUrl != currentBookUrl) {
            loadGuard.clear()
            preDownloader.reset()
            directoryLoadingJob?.cancel()
            directoryLoadFailed = false
            reviewCountBookUrl = currentBookUrl
            paragraphLayoutCache.clear()
        }
        // 书籍切换时清空已处理内容缓存
        if (processedContentBookUrl != currentBookUrl) {
            processedContentCache.clear()
            processedContentBookUrl = currentBookUrl
        }
        loadGuard.launch(index) {
            // 1. 同步 shared 状态字段（callback 通知）
            readBook.loadChapter(index)

            // 2. 章节列表：内存优先，内存没有再查库，仍空则单飞回源重解析（ensureChapterListLoaded）。
            // 目录加载独立于本章任务（单飞任务不参与同章替换取消）：打开初期 relayout 的同章替换
            // 不会掐断网络目录拉取。未入架书目录不落库，拉取被取消会致 chapterSize 滞留 0、
            // loadContent 越界静默 return → 永久"加载数据中"；对照原版时序：目录 await 就绪后才 loadContent。
            val book = readBook.book.value ?: return@launch
            val chapterList = ensureChapterListLoaded()

            if (chapterList.getOrNull(index) == null) {
                // 章节序号越界：显示占位页
                showMessageChapter("无章节内容", index, chapterList.size)
                return@launch
            }

            // 3. 跳章（对照 app 端 openChapter）：清滑窗 + 落位 + 回写内存 book 实体；同章重载保留 durChapterPos
            if (index != readBook.durChapterIndex.value) {
                readBook.clearTextChapter()
                readBook.updateDurChapterIndex(index)
                readBook.updateDurChapterPos(chapterPos ?: 0)
                loadGuard.cancelOutside(readBook.durChapterIndex.value)
                // 对齐原版 ReadBook.openChapter 末尾的 saveRead(): 目录/详情等消费方读的是内存
                // book 实体的 durChapterIndex/Pos/Title, 不回写就停在跳转前那一章
                readBook.saveRead()
            } else if (chapterPos != null) {
                readBook.updateDurChapterPos(chapterPos)
            }

            // 4. 当前章优先装载，前后章异步预载（对照 app 端 loadContent 三章同载）;
            //    前后章属预下载范畴: preDownloadNum=0 时不预载, 翻章走 moveToNext/PrevChapter
            //    按需装载 (2026-08-29 用户拍板, 偏离原版三章同载)
            loadContent(index)
            loadNeighborChapters(index)
        }
    }

    /**
     * 前后章是否主动预载。
     *
     * 前后章装载属预下载范畴：preDownloadNum=0（用户关闭预下载）时只装当前章，
     * 翻章走 moveToNext/PrevChapter 的按需装载（2026-08-29 用户拍板，有意偏离原版三章同载；
     * 音视频侧因资源直链带时效签名走另一套 ±1 预解析，不受此开关影响）。
     */
    private fun neighborLoadEnabled(): Boolean = AppConfigProviders.get().preDownloadNum > 0

    /** 预载前后各一章（受 [neighborLoadEnabled] 门控）。 */
    private fun loadNeighborChapters(center: Int) {
        if (!neighborLoadEnabled()) return
        loadGuard.launch(center + 1) { loadContent(center + 1) }
        loadGuard.launch(center - 1) { loadContent(center - 1) }
    }

    /**
     * 装载三章窗口：当前章优先，前后章其次。
     *
     * @param includeNeighbors true = 无条件带前后章（目录重解析/清整书缓存后整窗重载用：
     *   缓存已清空，前后章留着旧排版会与新目录/新缓存不一致）；false = 受
     *   [neighborLoadEnabled] 门控
     */
    private fun loadChapterWindow(center: Int, includeNeighbors: Boolean = false) {
        if (includeNeighbors) {
            for (index in chapterWindowIndices(center)) {
                loadGuard.launch(index) { loadContent(index) }
            }
            return
        }
        loadGuard.launch(center) { loadContent(center) }
        loadNeighborChapters(center)
    }

    /**
     * 回源重新解析目录（对照 app 端 `ReadBookViewModel.loadChapterListAwait`）：
     * 未加入书架的书只更新内存章节表，不落库（与原版 inBookshelf 守卫一致，也避免外键失败）。
     */
    private suspend fun loadChapterListFromSource(book: Book): List<BookChapter> {
        // 核心逻辑提取为顶层 [fetchChapterListFromSource] (供音频/RSS 等复用), 此处保留
        // readBook.updateChapterList 成员写入
        val list = fetchChapterListFromSource(book, readBook.bookSource.value)
        readBook.updateChapterList(list)
        return list
    }

    /**
     * 确保当前书目录就绪（对照原版时序：upBook → loadChapterList await 就绪后才 loadContent）。
     *
     * 内存目录有效直接返回（未入架书目录只在内存，查库覆盖会清成空目录）；否则查库（在架书路径）；
     * 仍空则启动/等待单飞网络拉取（[startDirectoryLoad]）。拉取失败返回空列表且置失败标志，
     * 不自动重试（对照原版失败后需手动"更新目录"）。
     */
    private suspend fun ensureChapterListLoaded(): List<BookChapter> {
        val book = readBook.book.value ?: return emptyList()
        readBook.chapterList.value.firstOrNull()?.let {
            if (it.bookUrl == book.bookUrl) return readBook.chapterList.value
        }
        if (directoryLoadFailed) return emptyList()
        // 书源解析缓存（对照 app 端 ReadBook.upWebBook，本地书为 null；网络拉取/正文下载复用）
        if (book.isLocal) {
            readBook.updateBookSource(null)
        } else if (readBook.bookSource.value?.bookSourceUrl != book.origin) {
            readBook.updateBookSource(
                AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
            )
        }
        // 库里查目录（在架书路径；未入架书目录不落库，查不到）
        val dbList = AppDbProviders.get().bookChapterDao.getChapterList(book.bookUrl)
        if (dbList.firstOrNull()?.bookUrl == book.bookUrl) {
            readBook.updateChapterList(dbList)
            return dbList
        }
        // 未入架书的目录只在内存: 详情页/目录页已把内存目录写入 IntentData.chapterList
        // (对照原版 upBook 的 IntentData 交接, 此处直接接管, 免去回源重拉目录的加载空白)。
        // 顺序在库查询之后: 在架书优先 DB (目录页反转等操作已持久化, DB 是最新权威),
        // IntentData 只兜底未落库书 (在架书即使 IntentData 残留也不消费, 防止旧实例污染)。
        IntentData.chapterList?.takeIf { it.firstOrNull()?.bookUrl == book.bookUrl }?.let {
            readBook.updateChapterList(it)
            return it
        }
        // 内存和库都没有目录：单飞回源重解析（对照 app 端 upBook 的
        // `!inBookshelf || totalChapterNum == 0 || 库里查空` → loadChapterList 分支）
        startDirectoryLoad(book).join()
        val list = readBook.chapterList.value
            .takeIf { it.firstOrNull()?.bookUrl == book.bookUrl } ?: emptyList()
        if (list.isEmpty()) directoryLoadFailed = true
        return list
    }

    /**
     * 启动目录加载任务（单飞）：已存在进行中任务则复用；任务内校验书未切换再更新内存目录，
     * 避免旧书拉取结果污染新书状态。切书/销毁时随 scope 取消。
     */
    private fun startDirectoryLoad(book: Book): Job {
        val job = synchronized(syncLock) {
            directoryLoadingJob?.let { return it }
            val newJob = scope.launch {
                val list = fetchChapterListFromSource(book, readBook.bookSource.value)
                if (readBook.book.value?.bookUrl == book.bookUrl) {
                    readBook.updateChapterList(list)
                }
            }
            directoryLoadingJob = newJob
            newJob
        }
        // 注册必须在锁外：job 已完成时 invokeOnCompletion 同线程直跑处理器，
        // 而处理器要取 syncLock（atomicfu 的锁在 Native 端不可重入）
        job.invokeOnCompletion {
            synchronized(syncLock) {
                if (directoryLoadingJob === job) directoryLoadingJob = null
            }
        }
        return job
    }

    /**
     * 装载单章正文并按滑窗归位（对照 app 端 `ReadBook.loadContentAwait`）：
     * 缓存未命中经 [downloadAwait] 联网，失败文案与原版一致作为正文排版展示。
     *
     * @param silent true = 段评迟到后的双缓冲静默重载：不先刷"加载数据中…"占位页，
     *   当前章保持旧排版直到新排版就绪再原子替换
     */
    private suspend fun loadContent(index: Int, silent: Boolean = false) {
        if (index < 0) return
        // 目录未就绪时先确保：打开初期 relayout 的同章替换会取消 loadChapter 主任务
        //（其中含网络目录拉取），重载任务在此补上目录前置，避免未入架书（目录不落库）
        // chapterSize 滞留 0 后本方法越界静默 return → 永久"加载数据中"。
        // 对照原版时序：loadContent 调用前目录必已由 loadChapterList await 就绪。
        val book = readBook.book.value ?: return
        if (readBook.chapterList.value.firstOrNull()?.bookUrl != book.bookUrl) {
            val list = ensureChapterListLoaded()
            if (list.isEmpty()) {
                showMessageChapter("无章节内容", index, 0)
                return
            }
        }
        if (index >= readBook.chapterSize) return
        if (!isInChapterWindow(index, readBook.durChapterIndex.value)) return
        if (!loadGuard.tryAdd(index)) return
        // 当前章未装载：先刷新页面流，展示"加载数据中…"占位（对照原版 moveToNextChapter /
        // openChapter 的 upContent 后 pageFactory 无章兜底页；msg 优先，不覆盖消息页）。
        // 装载完成后由 contentLoadFinish → applyCurChapterPages 替换为正文。
        if (!silent && index == readBook.durChapterIndex.value && readBook.msg == null) {
            syncPageFlows()
        }
        try {
            val chapter = resolveChapter(book, index, readBook.chapterList.value) ?: return
            // 与 app ReadBook.loadContent 一致：正文 IO 前先并行启动段评数请求，正文缓存命中也不阻塞。
            val countDeferred = startReviewCountFetchAsync(book, chapter)
            // 正文缓存读取是同步文件 IO (JvmBookStorage.readAllBytes, MB 级), 必须切 IO 线程
            val cached = withContext(IoDispatcher) {
                BookStorageProviders.get().getContent(book, chapter)
            }
            val content = cached ?: downloadAwait(book, chapter)
            // 原版在 contentLoadFinish 入口先 removeLoading；先释放守卫，确保段评迟到触发的重排
            // 可以立即重新加载同章，不会被本次 finally 尚未执行的 loading 标记挡住。
            loadGuard.release(index)
            contentLoadFinish(book, chapter, content, countDeferred)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("加载正文出错\n${e.message}", e)
            // 正文处理/排版抛错也要落占位章: 不落则上面铺的"加载数据中…"永不被替换, 阅读页
            // 永久卡住, 等本章排版的调用方 (skipToSearch) 也永远等不到本章。
            // 只作用于当前章: showMessageChapter 写的是滑窗 0 位, 前后章预载失败不许顶掉在看的章
            if (index == readBook.durChapterIndex.value) {
                showMessageChapter("加载正文失败\n${e.message}", index, readBook.chapterSize)
            }
        } finally {
            loadGuard.release(index)
        }
    }

    /**
     * 与正文加载并行启动段评数 IO，按 chapter.index 复用 Deferred。
     * 条件和 app ReadBook.startReviewCountFetchAsync 完全一致。
     */
    private fun startReviewCountFetchAsync(
        book: Book,
        chapter: BookChapter,
    ): Deferred<Map<Int, Int>?>? {
        val source = readBook.bookSource.value ?: return null
        if (!source.enabledReview) return null
        if (source.ruleReview.isNullOrEmpty()) return null
        val rule = source.reviewRule
        if (rule.reviewUrl.isNullOrBlank()) return null
        if (rule.reviewCountRule.isNullOrBlank()) return null
        synchronized(syncLock) {
            reviewCountDeferred[chapter.index]?.let { return it }
            return scope.async(IoDispatcher) {
                WebBook.getReviewCountAwait(source, book, chapter).getOrNull()
            }.also { reviewCountDeferred[chapter.index] = it }
        }
    }

    /**
     * 段评数迟于当前章排版到达时触发轻量就地补丁或双缓冲静默切换重排；仅当前章且章节对象仍相同时生效。
     */
    private fun scheduleReviewRelayoutIfNeeded(
        deferred: Deferred<Map<Int, Int>?>?,
        chapter: BookChapter,
        textChapter: TextChapterShared,
    ) {
        if (deferred == null || textChapter.reviewCountApplied) return
        scope.launch {
            val map = deferred.await() ?: return@launch
            if (map.isEmpty()) return@launch
            if (chapter.index != readBook.durChapterIndex.value) return@launch
            if (readBook.curTextChapter.value !== textChapter) return@launch
            synchronized(syncLock) {
                reviewCountDeferred[chapter.index] = CompletableDeferred(map)
            }

            val cfg = _layoutConfig.value
            val measurer = TextMeasurerProviders
                .createOrNull(
                    cfg.textSizePx, cfg.letterSpacingPx, cfg.textFontPath, cfg.contentWeight,
                )
                ?: SimpleTextMeasurer(
                    textSizePx = cfg.textSizePx,
                    letterSpacingPx = cfg.letterSpacingPx,
                    descent = cfg.textSizePx * 0.2f,
                )
            val titleMeasurer = TextMeasurerProviders
                .createOrNull(
                    cfg.titleSizePx, cfg.letterSpacingPx, cfg.textFontPath, cfg.titleWeight,
                )
                ?: SimpleTextMeasurer(
                    textSizePx = cfg.titleSizePx,
                    letterSpacingPx = cfg.letterSpacingPx,
                    descent = cfg.titleSizePx * 0.2f,
                )

            // 1. 尝试轻量就地补丁（若末行剩余空间可容纳气泡且不引发换行折行）
            if (textChapter.tryPatchReviewCounts(
                    reviewCountMap = map,
                    reviewChar = "▨",
                    measurer = measurer,
                    titleMeasurer = titleMeasurer,
                    visibleWidth = cfg.visibleWidth,
                    paddingLeft = cfg.paddingLeft,
                    paddingRight = cfg.paddingRight,
                    viewWidth = cfg.viewWidth,
                    doublePage = cfg.doublePage,
                )
            ) {
                // Patch 成功：更新已处理缓存中的 reviewCountMap，并刷新页面流与绘制缓存
                processedContentCache[chapter.index]?.let { cached ->
                    processedContentCache[chapter.index] = cached.copy(reviewCountMap = map)
                }
                bumpPageContentVersion()
                syncPageFlows()
                return@launch
            }

            // 2. 双缓冲静默切换机制：消除 clearTextChapter() 调用（避免白屏与视觉闪烁），
            // 在后台协程中完成新 TextChapter 的排版加载，排版完成后原子替换 curChapter / curTextChapter
            loadGuard.launch(chapter.index) {
                val cached = processedContentCache[chapter.index]
                if (cached != null) {
                    val updated = cached.copy(reviewCountMap = map)
                    processedContentCache[chapter.index] = updated
                    relayoutFromCache(chapter.index, updated, silent = true)
                } else {
                    loadContent(chapter.index, silent = true)
                }
            }
            // 前后章属预下载范畴: preDownloadNum=0 时不重载 (同 loadChapter 的预载口径)
            loadNeighborChapters(chapter.index)
        }
    }

    /** 联网拉取正文（对照 app 端 ReadBook.downloadAwait，只经 CacheBookShared 公开 API） */
    private suspend fun downloadAwait(book: Book, chapter: BookChapter): String {
        val bookSource = readBook.bookSource.value
        return if (bookSource != null) {
            val cacheBook = CacheBookShared.getOrCreate(bookSource, book)
            if (cacheBook.chapterList == null) {
                cacheBook.chapterList = readBook.chapterList.value
            }
            cacheBook.downloadAwait(chapter)
        } else {
            val msg = if (book.isLocal) "无内容" else "没有书源"
            "加载正文失败\n$msg"
        }
    }

    /**
     * 排版并按滑窗 offset 归位（对照 app 端 `contentLoadFinish` + `processContent`：
     * ContentProcessor 替换净化 / 简繁转换 / 重新分段 / 去重复标题 → 排版 → offset -1/0/+1 三分支）。
     */
    private suspend fun contentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        countDeferred: Deferred<Map<Int, Int>?>? = null,
    ) {
        if (!isInChapterWindow(chapter.index, readBook.durChapterIndex.value)) return
        val processor = ContentProcessorProviders.get()
        val displayTitle = chapter.getDisplayTitle(
            processor.getTitleReplaceRules(book),
            book.getUseReplaceRule(),
        )
        // textList 的项边界、空行、首尾空白和 HTML 图片标签均属于排版输入，不能先压平后
        // split/trim/filter。
        val bookContent = processor.getBookContent(
            book = book,
            chapter = chapter,
            content = content,
            includeTitle = false,
            useReplace = true,
        )
        val parsedParagraphs = ChapterContentParserShared.parse(bookContent)
        // 原版排版开始时只非阻塞读取已完成的 Deferred；未完成则先按无段评排版，保证即开即用。
        val reviewCountMap = countDeferred?.takeIf { it.isCompleted }?.await()
        // 图片解析器只创建一次，首次排版和 resize 重排共用
        val imageResolver = ImageResolverProviders.createOrNull(
            book, chapter, readBook.bookSource.value,
        )
        val pages = buildLayout().layout(
            displayTitle = displayTitle,
            contents = bookContent.textList,
            chapterIndex = chapter.index,
            chapterSize = readBook.chapterSize,
            reviewCountMap = reviewCountMap,
            parsedParagraphs = parsedParagraphs,
            imageResolver = imageResolver,
            imageStyle = book.config.imageStyle,
        )
        val textChapter = TextChapterShared(
            chapterIndex = chapter.index,
            pages = pages,
            reviewCountApplied = reviewCountMap != null,
            effectiveReplaceRules = bookContent.effectiveReplaceRules,
            sameTitleRemoved = bookContent.sameTitleRemoved,
        )
        pages.forEach { it.textChapter = textChapter }
        // 缓存已处理内容，视口变化时只重排版
        processedContentCache[chapter.index] = ProcessedChapterContent(
            chapter = chapter,
            displayTitle = displayTitle,
            textList = bookContent.textList,
            parsedParagraphs = parsedParagraphs,
            effectiveReplaceRules = bookContent.effectiveReplaceRules,
            reviewCountMap = reviewCountMap,
            imageResolver = imageResolver,
            sameTitleRemoved = bookContent.sameTitleRemoved,
        )
        // 排版期间可能已切章，以最新 durChapterIndex 归位滑窗（原版 when(offset) 三分支，超窗丢弃）
        val durIndex = readBook.durChapterIndex.value
        when (chapterWindowSlotOf(chapter.index, durIndex)) {
            null -> return

            ChapterWindowSlot.CUR -> {
                // 滚动模式连续跨章装载：保留滚动偏移（对照原版 moveToNextChapter →
                // loadContent(resetPageOffset = false)，避免占位页被正文替换时从章首跳变）；
                // 其余装载路径（打开书/菜单跳章/重排）照旧归零
                val crossing = consumeScrollCrossingPending()
                readBook.updateTextChapter(0, textChapter)
                applyCurChapterPages(textChapter, resetOffset = !crossing)
                scheduleReviewRelayoutIfNeeded(countDeferred, chapter, textChapter)
            }

            ChapterWindowSlot.PREV, ChapterWindowSlot.NEXT -> {
                readBook.updateTextChapter(chapter.index - durIndex, textChapter)
                // 相邻章装载完成：刷新页面流，替换章边界的"加载数据中…"占位页
                // （对照原版 upContent 后 pageFactory.nextPage/prevPage 立即取到新章页面）
                syncPageFlows()
            }
        }
    }

    /**
     * 复用已缓存的处理结果只重排版（视口变化 / 段评迟到时调用，跳过下载/ContentProcessor/解析/JS执行）。
     * 复用 imageResolver（其内部 sizes 缓存避免重复网络请求）。
     *
     * @param silent true = 段评迟到后的双缓冲静默重排：排完原子替换并保留滚动偏移与阅读位置；
     *   false = 视口变化的纯 UI 重排：偏移归零且不触发 preDownload（避免网络请求/JS执行）
     */
    private suspend fun relayoutFromCache(
        index: Int,
        cached: ProcessedChapterContent,
        silent: Boolean = false,
    ) {
        if (!isInChapterWindow(index, readBook.durChapterIndex.value)) return
        val book = readBook.book.value ?: return
        val pages = buildLayout().layout(
            displayTitle = cached.displayTitle,
            contents = cached.textList,
            chapterIndex = index,
            chapterSize = readBook.chapterSize,
            reviewCountMap = cached.reviewCountMap,
            parsedParagraphs = cached.parsedParagraphs,
            imageResolver = cached.imageResolver,
            imageStyle = book.config.imageStyle,
        )
        val textChapter = TextChapterShared(
            chapterIndex = index,
            pages = pages,
            reviewCountApplied = cached.reviewCountMap != null,
            effectiveReplaceRules = cached.effectiveReplaceRules,
            sameTitleRemoved = cached.sameTitleRemoved,
        )
        pages.forEach { it.textChapter = textChapter }
        val durIndex = readBook.durChapterIndex.value
        when (chapterWindowSlotOf(index, durIndex)) {
            null -> return

            ChapterWindowSlot.CUR -> {
                readBook.updateTextChapter(0, textChapter)
                if (silent) {
                    applyCurChapterPages(textChapter, resetOffset = false)
                } else {
                    // 纯 UI 重排：只更新页状态，不触发 preDownload（避免网络请求/JS执行）
                    pageList.clear()
                    pageList.addAll(textChapter.pages)
                    // 重排后行几何整体变化，滚动偏移归零 (对照旧 LOAD_CONTENT → resetPageOffset)
                    resetScrollOffset()
                    if (readBook.durChapterPos.value == Int.MAX_VALUE) {
                        readBook.updateDurChapterPos(textChapter.lastReadLength)
                    }
                    syncPageFlows()
                }
            }

            ChapterWindowSlot.PREV, ChapterWindowSlot.NEXT -> {
                readBook.updateTextChapter(index - durIndex, textChapter)
                // 相邻章重排完成：刷新页面流，替换章边界的"加载数据中…"占位页
                syncPageFlows()
            }
        }
    }

    /**
     * 翻到下一页（委托 [ReadBookShared.nextPage] 做 durChapterPos 位移，对照 app 端 moveToNextPage）。
     * 已到章末返回 false，由调用方触发 [moveToNextChapter]。
     */
    fun nextPage(): Boolean {
        if (!readBook.nextPage()) return false
        // 手动翻页离开朗读页即清高亮（对照原版 moveToNextPage 的 removePageAloudSpan）
        _ttsHighlight.value = null
        syncPageFlows()
        // 对照 app 端 setPageIndex → curPageChanged → preDownload
        preDownload()
        return true
    }

    /** 翻到上一页；已到章首返回 false，由调用方触发 [moveToPrevChapter]。 */
    fun prevPage(): Boolean {
        if (!readBook.prevPage()) return false
        syncPageFlows()
        preDownload()
        return true
    }

    /**
     * 切到下一章（对照 app 端 `ReadBook.moveToNextChapter`）：三章滑窗前移，
     * 命中已排版的 next 章直接展示，未命中再装载；并预载新的下一章。
     *
     * @param resetOffset true=滚动模式切章后滚动偏移归零，新章严格从第一页顶部开始
     *   （方向键切章用）；默认 false 保留偏移连续折算（对照原版 toFirst=false，
     *   自动切章/菜单/朗读等入口沿用）
     * @return true 表示已触发切章；false 表示已到末章
     */
    fun moveToNextChapter(resetOffset: Boolean = false): Boolean {
        val curIndex = readBook.durChapterIndex.value
        if (curIndex < readBook.simulatedChapterSize - 1) {
            readBook.updateDurChapterPos(0)
            readBook.updateDurChapterIndex(curIndex + 1)
            loadGuard.cancelOutside(readBook.durChapterIndex.value)
            readBook.slideTextChaptersNext()
            val newCur = readBook.curTextChapter.value
            if (newCur != null) {
                // 滚动切章: 默认不归零滚动偏移 (offset 连续折算在 applyScrollDelta), 对照原版 toFirst=false;
                // 方向键切章 (resetOffset=true) 归零, 新章从第一页顶部开始
                applyCurChapterPages(newCur, resetOffset = resetOffset)
            }
            if (newCur == null) {
                // 当前章未装载：立即刷新三页流展示"加载数据中…"占位（对照原版
                // moveToNextChapter 的 upContent 后 pageFactory 无章兜底页），
                // 装载完成由 contentLoadFinish 替换为正文
                syncPageFlows()
                loadGuard.launch(curIndex + 1) { loadContent(curIndex + 1) }
            }
            // 后二章预载属预下载范畴: preDownloadNum=0 时不预载 (按需装载只保留上面的新当前章)
            if (neighborLoadEnabled()) {
                loadGuard.launch(curIndex + 2) { loadContent(curIndex + 2) }
            }
            // 对齐原版 ReadBook.moveToNextChapter 末尾的 saveRead(): 翻章即回写内存
            // book 实体 (详情页 IntentData 传递依赖内存实时性) + 落库 (书架靠失效推送刷新)
            readBook.saveRead()
            return true
        }
        return false
    }

    /**
     * 是否可以切到下一章（不实际触发切章）。
     *
     * 供 [io.legado.app.ui.book.read.page.delegate.PageDelegateShared.hasNext] 判定用：
     * 章节末页时本章节无下一页，但若有下一章则仍允许翻页动画继续（动画停止时调
     * [moveToNextChapter] 切章）。
     */
    fun canMoveToNextChapter(): Boolean {
        val cur = readBook.durChapterIndex.value
        return cur + 1 < readBook.simulatedChapterSize
    }

    /**
     * 是否可以切到上一章（不实际触发切章）。
     *
     * 供 [io.legado.app.ui.book.read.page.delegate.PageDelegateShared.hasPrev] 判定用：
     * 章节首页时本章节无上一页，但若有上一章则仍允许翻页动画继续（动画停止时调
     * [moveToPrevChapter] 切章）。
     */
    fun canMoveToPrevChapter(): Boolean {
        val cur = readBook.durChapterIndex.value
        return cur - 1 >= 0
    }

    /**
     * 切到上一章（对照 app 端 `ReadBook.moveToPrevChapter`）：三章滑窗后移。
     *
     * @param toLast true=落到上一章末页（durChapterPos = prev.lastReadLength，
     *   未预载时用 Int.MAX_VALUE 编码，排版后自然落到 pages.lastIndex；原版默认值）；
     *   false=落到上一章第一页
     * @param resetOffset true=滚动模式切章后滚动偏移归零，新章严格从第一页顶部开始
     *   （方向键切章用）；默认 false 保留偏移连续折算（对照原版 toFirst=false，
     *   自动切章/菜单/朗读等入口沿用）
     * @return true 表示已触发切章；false 表示已到首章
     */
    fun moveToPrevChapter(toLast: Boolean = true, resetOffset: Boolean = false): Boolean {
        val curIndex = readBook.durChapterIndex.value
        if (curIndex > 0) {
            val prevPos = if (toLast) {
                readBook.prevTextChapter.value?.lastReadLength ?: Int.MAX_VALUE
            } else {
                0
            }
            readBook.updateDurChapterPos(prevPos)
            readBook.updateDurChapterIndex(curIndex - 1)
            loadGuard.cancelOutside(readBook.durChapterIndex.value)
            readBook.slideTextChaptersPrev()
            val newCur = readBook.curTextChapter.value
            if (newCur != null) {
                // 滚动切章: 默认不归零滚动偏移 (offset 连续折算在 applyScrollDelta), 对照原版 toFirst=false;
                // 方向键切章 (resetOffset=true) 归零, 新章从第一页顶部开始
                applyCurChapterPages(newCur, resetOffset = resetOffset)
            }
            if (newCur == null) {
                // 当前章未装载：立即刷新三页流展示"加载数据中…"占位（对照原版
                // moveToPrevChapter 的 upContent 后 pageFactory 无章兜底页）
                syncPageFlows()
                loadGuard.launch(curIndex - 1) { loadContent(curIndex - 1) }
            }
            // 前二章预载属预下载范畴: preDownloadNum=0 时不预载 (同 moveToNextChapter)
            if (neighborLoadEnabled()) {
                loadGuard.launch(curIndex - 2) { loadContent(curIndex - 2) }
            }
            // 对齐原版 ReadBook.moveToPrevChapter 末尾的 saveRead()
            readBook.saveRead()
            return true
        }
        return false
    }

    /**
     * 持久化阅读进度到 books 表。
     *
     * 与 app 端 `ReadBookActivity.onStop` → `appDb.bookDao.updateProgress` 编排对应：
     * 取 [ReadBookShared] 当前 durChapterIndex / durChapterPos / 章节标题，PATCH 进
     * books 表（避免整行 update 冲掉后台 updateToc/refreshBookInfo 写入的最新元数据）。
     *
     * 平台侧退出阅读时经 [onCleared] 间接触发（落库 + WebDav 上传）。
     */
    fun saveProgress() {
        scope.launch { saveProgressAwait() }
    }

    /** [saveProgress] 的 suspend 核心，供进度上传前"先落库再上传"复用（原版 onPause 先 saveRead 再 uploadProgress）。 */
    private suspend fun saveProgressAwait() {
        val book = readBook.book.value ?: return
        val index = readBook.durChapterIndex.value
        ChapterProgressStore.save(
            book = book,
            durChapterIndex = index,
            durChapterPos = encodedChapterPos(),
            chapter = resolveChapter(book, index, readBook.chapterList.value),
        )
    }

    /**
     * 章内位置的落库编码：末页停留时取负编码「停在章末」（原版 ReadBook.saveRead），
     * 重进时由 [ReadBookShared.loadBook] 归一还原。
     */
    private fun encodedChapterPos(): Int {
        val textChapter = readBook.curTextChapter.value
        val atLastPage = textChapter != null && textChapter.isLastIndex(readBook.durPageIndexValue)
        return readBook.durChapterPos.value * (if (atLastPage) -1 else 1)
    }

    // region WebDav 进度同步（对照 app 端 BaseReadViewModel.syncProgress/uploadProgress + ReadBookViewModel.initBook）
    /** 进度同步专用作用域：不随 UI scope 取消。
     * 原版 uploadProgress 走进程级 MainScope（Coroutine.async），退出阅读时 VM 已 cleared 上传也不被打断；
     * shared 版等价用独立 [screenModelScope]（[scope] 桌面端为 rememberCoroutineScope，dispose 即取消）。
     */
    private val progressSyncScope = screenModelScope("阅读进度同步", IoDispatcher)

    /**
     * 打开书时同步云进度（对照原版 ReadBookViewModel.initBook 的同步段）：
     * 每次打开书都触发，仅同书 + 朗读运行中跳过（原版 `!(isSameBook && BaseReadAloudService.isRun)`）。
     * 原版另有 chapterChanged 跳过（intent extra，shared 无对应概念）与 inBookshelf 守卫；
     * 同步入口由 ReaderScreenModel.initBook 调用，换章等 loadChapter 路径不触发。
     *
     * 注意：原版 initBook 每次调用都同步，不存在“每本书只同步一次”的限频；
     * 云端较新时弹确认框（[ReadBookEvents.newProgressConfirm]），确认后云端进度回写，
     * 下次进入比对相等不再弹，不会重复打扰。
     */
    fun syncProgressOnBookOpen(book: Book, isSameBook: Boolean) {
        if (isSameBook && ReadBookPlatforms.get().isReadAloudRun) return
        if (book.isNotShelf) return
        pullCloudProgress(book)
    }

    /**
     * 打开书时拉取云进度并三路比对（原版 BaseReadViewModel.syncProgress，syncBookProgressPlus 路径）：
     * - 云端无进度或本地较新 → 上传本地进度
     * - 云端较新 → 发 [ReadBookEvents.newProgressConfirm] 确认事件（replay=1），
     *   UI 弹窗后由 [confirmSyncProgress] / [dismissSyncProgress] 收尾
     * - 相等 → 无操作（原版 syncSuccessAction 仅手动菜单路径使用）
     *
     * 网络/解析失败 [AppWebDavShared.getBookProgress] 内部已捕获返回 null（与当前 app 端
     * AppWebDav.getBookProgress 委托实现同语义），走上传分支由上传自身的失败捕获兜底。
     */
    private fun pullCloudProgress(book: Book) {
        ChapterProgressStore.pullCloud(
            scope = progressSyncScope,
            book = book,
            enabled = PreferenceProviders.get().getBoolean(PreferKey.syncBookProgressPlus, false),
            onNewProgress = { ReadBookEvents.postConfirmNewProgress(it) },
        )
    }

    /**
     * 跳到指定进度并重排当前章（原版 ReadBook.setProgress 的等价物）：越界守卫 + index/pos 未变则跳过。
     *
     * 带进度的跳转一律走本方法：只有本路径会排版并回填三章滑窗。
     *
     * 原版 setProgress：赋 index/pos + clearTextChapter + loadContent(resetPageOffset=true)，
     * 无 saveRead。故不借 loadChapter 的 chapterPos 落位 (跳章分支会 saveRead、同章分支不清
     * 滑窗)：这里自行成对写 index/pos，让 loadChapter 只当同章重载
     *
     * @return false = 越界或进度未变, 未发生跳转
     */
    fun setProgress(progress: BookProgress): Boolean {
        if (progress.durChapterIndex >= readBook.chapterSize) return false
        if (readBook.durChapterIndex.value == progress.durChapterIndex &&
            readBook.durChapterPos.value == progress.durChapterPos
        ) return false
        readBook.clearTextChapter()
        readBook.updateDurChapterIndex(progress.durChapterIndex)
        readBook.updateDurChapterPos(progress.durChapterPos)
        loadChapter(progress.durChapterIndex)
        return true
    }

    /**
     * 用户确认同步云端进度（原版 ReadBookActivity.sureNewProgress okButton → ReadBook.setProgress）：
     * 跳转后落库。
     */
    fun confirmSyncProgress(progress: BookProgress) {
        ReadBookEvents.clearNewProgressConfirm()
        if (setProgress(progress)) saveProgress()
    }

    /** 用户取消同步云端进度：仅清事件 replay 缓存，避免 UI 重建时重复弹窗。 */
    fun dismissSyncProgress() {
        ReadBookEvents.clearNewProgressConfirm()
    }

    /**
     * 落库并上传当前阅读进度（原版 BaseReadViewModel.uploadProgress）。
     *
     * 上传时机与原版一致：平台 onPause（Activity 生命周期桥接，见 ReaderScreenModel.onPause）
     * 与退出阅读（[onCleared]）时触发，不再切章即上传。
     */
    fun uploadProgress() {
        readBook.book.value ?: return
        progressSyncScope.launch {
            uploadProgressAwait()
        }
    }

    /**
     * 上传核心：先 [saveProgressAwait] 落库（原版 onPause 先 saveRead 再 uploadProgress），
     * 再读 DB 最新行构造 BookProgress 上传（[saveProgress] 只 PATCH DB 不回写内存 book 实体，
     * 直接用 readBook.book.value 会带旧 index/pos）。
     * 上传成功后持久化 syncTime（原版 book.update()；这里 update 的是刚读出的短窗口快照行，
     * 避免长持有实体整行冲写并发修改）。
     */
    private suspend fun uploadProgressAwait() {
        val book = readBook.book.value ?: return
        val index = readBook.durChapterIndex.value
        // 落库 → 通知书架重查 → 读 DB 最新行上传（实现已收敛至 [ChapterProgressStore]，四模式共用）
        ChapterProgressStore.saveAndUpload(
            book = book,
            durChapterIndex = index,
            durChapterPos = encodedChapterPos(),
            chapter = resolveChapter(book, index, readBook.chapterList.value),
        )
    }

    /**
     * 退出阅读界面时调用（唯一调用方 [io.legado.app.ui.book.read.ReaderScreenModel.onCleared]）：
     * 摘注册 + 取消在途任务 + 清理未入架书。
     *
     * 落库上传不在这里做：[uploadProgress] 每次调用都是一次真实 WebDav PUT（无去重/节流，
     * 见 AppWebDavShared.uploadBookProgress），由宿主按"活跃期结束"调一次即可。
     */
    fun onCleared() {
        ActiveReadBookRegistry.detachViewModel(this)
        ActiveReadAloudHostPorts.detach(readAloudOwner)
        loadGuard.clear()
        directoryLoadingJob?.cancel()
        reviewCountBookUrl = null
        processedContentCache.clear()
        processedContentBookUrl = null
        paragraphLayoutCache.clear()
        preDownloader.reset()
        // 未入架书退出后清理 DB 残留（对照原版 ReadBookActivity.onDestroy 的
        // `if (!ReadBook.inBookshelf && !isChangingConfigurations) removeFromBookshelf(null)` 兜底；
        // 走独立 scope，UI scope 取消不影响删除）。确定入架（已去 notShelf 标记）或已删除的书不重复处理。
        val book = readBook.book.value
        if (book != null && book.isNotShelf) {
            progressSyncScope.launch {
                AppDbProviders.get().bookDao.delete(book)
                book.addType(BookType.notShelf)
            }
        }
    }
    // endregion

    /**
     * 禁用当前书源 (对照 app 端 ReadBookViewModel.disableSource)。
     *
     * 取 readBook.book.value.origin 查 BookSource, 设 enabled=false 后 update 入库。
     * actual 平台若需附加 UI 反馈 (Toast / 退出阅读等), 在调用方处理。
     */
    fun disableSource() {
        val book = readBook.book.value ?: return
        scope.launch {
            val sourceDao = AppDbProviders.get().bookSourceDao
            val source = sourceDao.getBookSource(book.origin) ?: return@launch
            source.enabled = false
            sourceDao.update(source)
        }
    }

    // region 朗读事件回调 (对照 app 端 ReadBookActivity.observeLiveBus 的 ALOUD_STATE/MEDIA_BUTTON/TTS_PROGRESS 观察者)

    /**
     * 切换朗读播放/暂停 (对照 app 端 MEDIA_BUTTON isDown=false 分支 `ReadBook.readAloud(!BaseReadAloudService.pause)`)。
     *
     * 由 ReaderScreenModel 在媒体键事件中先停自动翻页 (autoPageStop) 再调本方法,
     * 对齐 app 端 ReadBookActivity.onClickReadAloud 的 autoPageStop + 朗读切换顺序。
     * 滚动模式未运行时从可视区首行定位起点 (旧语义: getReadAloudPos → durChapterPos +
     * openChapter → readAloud(startPos))。
     */
    fun toggleReadAloud() {
        val platform = ReadBookPlatforms.get()
        when {
            // 未运行: 从当前进度开始朗读 (滚动模式定位到可视区首行)
            !platform.isReadAloudRun -> {
                if (isScrollPageAnim) {
                    readAloudFromVisibleStart()
                } else {
                    readBook.readAloud()
                }
            }

            // 暂停: 恢复播放
            platform.isReadAloudPause -> readBook.readAloud(play = true)

            // 运行中: 暂停
            else -> platform.pauseReadAloud()
        }
    }

    /**
     * 滚动模式朗读起点定位 (对照 app 端 ContentTextView.getReadAloudPos :466-484)。
     *
     * 基于 [scrollOffset] 与 TextPage.lines 的 isVisible 算法: 从当前页 (相对偏移 =
     * scrollOffset) 起找第一个可见行, 页内容未填满视口时再扫下一页 (相对偏移 =
     * scrollOffset + 当前页高, 对照旧 relativeOffset(1)); 下一页顶已到视口底之下即止。
     *
     * @return (章节索引, 平移后的可见行); 无可视行时 null (旧语义: null → 从当前进度朗读)
     */
    fun getReadAloudPos(): Pair<Int, TextLine>? {
        if (!isScrollPageAnim) return null
        val curPage = _curTextPage.value ?: return null
        if (curPage.lines.isEmpty()) return null
        val visibleHeight = curPage.visibleHeight
        // 当前页 (对照旧 relativePage(0) + relativeOffset(0) = pageOffset)
        val curLine = findFirstVisibleLine(curPage, _scrollOffset.value.toFloat())
        if (curLine != null) return curPage.chapterIndex to curLine
        // 下一页: 仅当页内容未填满视口时可能有可见行 (对照旧 relativePos>0 的 break 条件)
        val nextPage = _nextTextPage.value ?: return null
        val nextOffset = _scrollOffset.value.toFloat() + curPage.height
        if (nextOffset >= visibleHeight) return null
        val nextLine = findFirstVisibleLine(nextPage, nextOffset)
        return if (nextLine != null) nextPage.chapterIndex to nextLine else null
    }

    /** 找页内第一个可见行, 返回平移后的副本 (对照旧 getReadAloudPos 的 copy + lineTop/lineBottom 平移) */
    private fun findFirstVisibleLine(
        page: TextPage,
        relativeOffset: Float,
    ): TextLine? {
        val lines = page.lines
        for (i in lines.indices) {
            val textLine = lines[i]
            if (textLine.isVisible(relativeOffset)) {
                return textLine.copy().apply {
                    lineTop += relativeOffset
                    lineBottom += relativeOffset
                }
            }
        }
        return null
    }

    /**
     * 滚动模式朗读起点: 从可视区首行开始朗读 (对照原版 onClickReadAloud 的
     * getReadAloudPos + durChapterPos/openChapter + readAloud(startPos) 分支)。
     *
     * 定位行语义: durChapterPos = line.chapterPosition, readAloud(startPos =
     * line.pagePosition) → 服务端 readAloudNumber = getReadLength(durPageIndex) + startPos
     * = 该行章节位置。跨章时 openChapter 跳章, 排版完成后经 [applyCurChapterPages]
     * 触发延迟朗读 (对照原版 openChapter success 回调时机); 无可视行回落当前进度。
     */
    fun readAloudFromVisibleStart() {
        val pos = getReadAloudPos()
        if (pos != null) {
            val (index, line) = pos
            if (readBook.durChapterIndex.value != index) {
                pendingReadAloudStart = line.pagePosition
                openChapter(index, line.chapterPosition)
            } else {
                readBook.updateDurChapterPos(line.chapterPosition)
                readBook.readAloud(startPos = line.pagePosition)
            }
        } else {
            readBook.readAloud()
        }
    }

    /** 跨章朗读起点: 目标章排版完成待触发的页内偏移 (对照原版 openChapter success 时机) */
    private var pendingReadAloudStart: Int? = null

    /**
     * 清除朗读高亮 (对照 app 端 ALOUD_STATE STOP/PAUSE 分支:
     * `page.removePageAloudSpan()` + `readView.upContent(resetPageOffset = false)`)。
     *
     * 高亮是 [ttsHighlight] 单一状态位，清空即全页失效，无需定位页。
     */
    fun clearAloudSpanForCurrentPage() {
        _ttsHighlight.value = null
    }

    /**
     * 朗读进度推进 (对照 app 端 TTS_PROGRESS sticky 观察者:
     * `ReadBook.durChapterPos = chapterStart` + `page.upPageAloudSpan(aloudSpanStart)` + `upContent()`)。
     *
     * - 仅朗读播放中推进（原版守卫 `BaseReadAloudService.isPlay()`，忽略停止/暂停后的粘性重放）
     * - `updateReadPosition` 等价原版 `durChapterPos = chapterStart` + `upContent`：
     *   跨页时页面流随之翻到朗读页（原版 pageIndex 由 durChapterPos 派生）
     * - 高亮只记 [ttsHighlight] 区间，绘制期投影成行（原版是往行上写 isReadAloud 标志位）
     */
    fun onTtsProgress(chapterStart: Int) {
        val platform = ReadBookPlatforms.get()
        if (!platform.isReadAloudRun || platform.isReadAloudPause) return
        if (readBook.curTextChapter.value == null) return
        updateReadPosition(chapterStart)
        setAloudHighlight(chapterStart)
    }
    // endregion

    // region 路由结果回调 (对照 app 端 ReadBookActivity 路由结果处理 + ReadBookViewModel 同名方法)

    /**
     * 请求重载目录 (对照 app 端 ReadBookViewModel.loadChapterList(book) + loadChapterListAwait(book))。
     *
     * 走 [loadChapterListFromSource] 回源重拉目录 (本地书重解析文件 / 网络书调 WebBook),
     * 成功后清当前章排版缓存并重载三章滑窗; 失败保持现状不破坏内存目录。
     */
    fun loadChapterList(book: Book) {
        // 对照原版 ReadBookActivity.loadChapterList：更新目录期间整页显示"更新目录中…"
        readBook.upMsg(appString(AppStringKey.toc_updateing))
        scope.launch {
            val list = loadChapterListFromSource(book)
            if (list.isEmpty()) {
                // 对照原版 loadChapterListAwait 失败分支：显示"加载目录失败"（目录保持现状）
                readBook.upMsg(appString(AppStringKey.error_load_toc))
            } else {
                directoryLoadFailed = false
                // 成功: 清当前章已处理内容缓存 + 重载滑窗 (对照 app 端 onChapterListUpdated 触发 loadContent)
                processedContentCache.remove(readBook.durChapterIndex.value)
                readBook.clearTextChapter()
                // 先清消息再重载：upMsg(null) 经 onUpContent 刷新页面流，
                // 当前章已清空时直接展示"加载数据中…"占位
                readBook.upMsg(null)
                val index = readBook.durChapterIndex.value
                loadChapterWindow(index)
            }
        }
    }

    /**
     * 替换规则变化后重载正文 (对照 app 端 ReadBookViewModel.replaceRuleChanged:
     * `ContentProcessor.upReplaceRules()` + `ReadBook.loadContent(resetPageOffset = false)`)。
     *
     * 调 [ContentProcessorProviders.get].upReplaceRules 刷新所有 ContentProcessor 实例的替换规则缓存,
     * 然后清当前章排版缓存并按 resetPageOffset=false 语义重排 (保留 durChapterPos 进度)。
     */
    fun replaceRuleChanged() {
        scope.launch {
            ContentProcessorProviders.get().upReplaceRules()
            val book = readBook.book.value ?: return@launch
            val index = readBook.durChapterIndex.value
            // 清已处理内容缓存, 强制重新走 ContentProcessor 链路 (含新替换规则)
            processedContentCache.remove(index)
            readBook.clearTextChapter()
            loadGuard.launch(index) { loadContent(index) }
        }
    }

    /**
     * 书源编辑保存后刷新书源引用 (对照 app 端 ReadBookViewModel.upBookSource(success)
     * + BaseReadViewModel.upSource/onUpSource: `ReadBook.bookSource = appDb.bookSourceDao.getBookSource(book.origin)`)。
     *
     * @param source 书源编辑保存后回传的已保存对象, 优先直接采用 (不查库, 同步生效);
     *   null 时兜底按 book.origin 查库 (历史调用方)
     *
     * 只更新引用不重载正文: 当前章已排版内容保持不变, 新书源 (含新 jslib, 保存时已
     * SharedJsScope.remove 旧作用域) 在后续 JS 调用/翻章/手动刷新时自然生效,
     * 与 app 端 upBookSource 行为一致。success 在书源更新完成后触发。
     */
    fun upBookSource(source: BookSource? = null, success: (() -> Unit)? = null) {
        if (source != null) {
            // 直接采用回传对象 (对齐 master onUpSource 语义, 无查库无竞态)
            readBook.updateBookSource(source)
            success?.invoke()
            return
        }
        scope.launch {
            val book = readBook.book.value
            if (book != null) {
                // 重新从 DB 加载书源 (对照 app 端 onUpSource)
                readBook.updateBookSource(
                    AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
                )
            }
            success?.invoke()
        }
    }

    /**
     * 子页返回后补载缺失章节 (对照 app 端 `ReadBook.loadOrUpContent`):
     * 当前章/前后章滑窗为空时补装载, 已装载的不重载 (不刷新正文)。
     * 用于书籍详情等子页返回阅读页后 (原版 bookInfoActivity 回调 else 分支)。
     */
    fun loadOrUpContent() {
        val book = readBook.book.value ?: return
        val durIndex = readBook.durChapterIndex.value
        if (readBook.curTextChapter.value == null) {
            loadGuard.launch(durIndex) { loadContent(durIndex) }
        }
        // 前后章属预下载范畴: preDownloadNum=0 时不补载 (同 ReadBookShared.loadOrUpContent 口径)
        if (neighborLoadEnabled()) {
            if (readBook.nextTextChapter.value == null && durIndex + 1 < readBook.chapterSize) {
                loadGuard.launch(durIndex + 1) { loadContent(durIndex + 1) }
            }
            if (readBook.prevTextChapter.value == null && durIndex - 1 >= 0) {
                loadGuard.launch(durIndex - 1) { loadContent(durIndex - 1) }
            }
        }
    }
    // endregion

    // region 内容管理 (对照 app 端 ReadBookViewModel 同名方法)

    /**
     * 翻到指定章节 (对照 app 端 ReadBookViewModel.openChapter -> ReadBook.openChapter)。
     *
     * 清三章滑窗 + 跳章 + 落位 + 重载当前章及前后章, 编排全在 [loadChapter] 内完成。
     *
     * @param index 章节序号
     * @param durChapterPos 章内字符位置 (默认 0 = 章首)
     * @param success 加载启动回调 (与 app 端 success 时机差异: app 端在 loadContent 完成后触发,
     *   shared 端 loadChapter 是 fire-and-forget, 这里在编排启动后立即触发, 供 UI 刷新菜单状态)
     */
    fun openChapter(index: Int, durChapterPos: Int = 0, success: (() -> Unit)? = null) {
        if (index !in 0 until readBook.chapterSize) return
        loadChapter(index, chapterPos = durChapterPos)
        success?.invoke()
    }

    /**
     * 从书架删除当前书 (对照 app 端 ReadBookViewModel.removeFromBookshelf + Book.delete 扩展)。
     *
     * 1:1 复刻 app 端 `Book.delete()` 行为: 删的是当前书则清 readBook.book 引用,
     * 从数据库删除, 标记 notShelf 类型。
     */
    fun removeFromBookshelf(success: (() -> Unit)?) {
        val book = readBook.book.value ?: return
        scope.launch {
            // 删的是当前书: 清 readBook.book 引用 (对照 app 端 Book.delete 的 ReadBook.book = null)
            if (readBook.book.value?.bookUrl == book.bookUrl) {
                readBook.bookValue = null
            }
            AppDbProviders.get().bookDao.delete(book)
            book.addType(BookType.notShelf)
            success?.invoke()
        }
    }

    /**
     * 加入书架（对照原版 BaseReadActivity.finish 弹窗确定分支 `currentBook?.save()`）。
     * 上架核心复用 [toggleBookshelfCore]（与详情页收藏同语义：去 notShelf 标记 + 同名书进度合并 + upsert）。
     */
    fun addToBookshelf(success: (() -> Unit)? = null) {
        val book = readBook.book.value ?: return
        val chapters = readBook.chapterList.value
        scope.launch {
            book.toggleBookshelfCore(inBookshelf = false, chapters = chapters)
            success?.invoke()
        }
    }

    /**
     * 刷新当前章及之后所有章节缓存 (对照 app 端 ReadBookViewModel.refreshContentAfter)。
     *
     * 删除 durChapterIndex 到末尾的所有章节正文缓存, 然后重载当前章 (resetPageOffset=false 保留进度)。
     */
    fun refreshContentAfter(book: Book) {
        scope.launch {
            val durIndex = readBook.durChapterIndex.value
            val chapterList = readBook.chapterList.value
            // 删除 durChapterIndex 之后所有章节缓存 (对照 app 端 getChapterList + delContent)
            val storage = BookStorageProviders.get()
            for (i in durIndex..chapterList.lastIndex) {
                storage.delContent(book, chapterList[i])
            }
            // 清当前章已处理内容缓存 + 重载 (对照 app 端 ReadBook.loadContent(false))
            processedContentCache.remove(durIndex)
            readBook.clearTextChapter()
            loadGuard.launch(durIndex) { loadContent(durIndex) }
        }
    }

    /**
     * 保存章节正文 (对照 app 端 ReadBookViewModel.saveContent)。
     *
     * 取当前章 BookChapter, 调 [BookHelpShared.saveContent] 落盘 + 发 EventBus 事件,
     * 然后清缓存重载当前章 (resetPageOffset=false 保留进度)。
     */
    fun saveContent(book: Book, content: String) {
        scope.launch {
            val durIndex = readBook.durChapterIndex.value
            val chapter = resolveChapter(book, durIndex, readBook.chapterList.value)
                ?: return@launch
            BookHelpShared.saveContent(book, chapter, content)
            // 清当前章已处理内容缓存 + 重载 (对照 app 端 ReadBook.loadContent(durChapterIndex, resetPageOffset=false))
            processedContentCache.remove(durIndex)
            readBook.clearTextChapter()
            loadGuard.launch(durIndex) { loadContent(durIndex) }
        }
    }

    /**
     * 翻转删除重复标题 (对照 app 端 ReadBookViewModel.reverseRemoveSameTitle)。
     *
     * 取当前章 TextChapterShared 的 sameTitleRemoved 标记, 取反后写 .nr 标记文件
     * (BookHelpShared.setRemoveSameTitleMarker), 然后重载当前章让 ContentProcessor 按新标记重新处理。
     */
    fun reverseRemoveSameTitle() {
        scope.launch {
            val book = readBook.book.value ?: return@launch
            val textChapter = readBook.curTextChapter.value ?: return@launch
            val durIndex = readBook.durChapterIndex.value
            val chapter = resolveChapter(book, durIndex, readBook.chapterList.value)
                ?: return@launch
            // 翻转去重标记 (对照 app 端 BookHelp.setRemoveSameTitle(book, chapter, !sameTitleRemoved))
            BookHelpShared.setRemoveSameTitleMarker(book, chapter, !textChapter.sameTitleRemoved)
            // 清当前章已处理内容缓存 + 重载 (对照 app 端 ReadBook.loadContent(durChapterIndex))
            processedContentCache.remove(durIndex)
            readBook.clearTextChapter()
            loadGuard.launch(durIndex) { loadContent(durIndex) }
        }
    }
    // endregion

    /**
     * 用 [layoutConfig] 构造 [SimpleChapterLayout] 实例。
     *
     * 每次调用新建（排版参数可能动态变化，如窗口尺寸变化后）。
     * 度量器取 [TextMeasurerProviders] 注册的平台真实字形实现（四端都注册 SkiaTextMeasurer）；
     * [SimpleTextMeasurer] 等宽近似只在未注册时兜底（实际只有单测环境）。
     */
    private fun buildLayout(): SimpleChapterLayout {
        val cfg = _layoutConfig.value
        val measurer = TextMeasurerProviders
            .createOrNull(
                cfg.textSizePx, cfg.letterSpacingPx, cfg.textFontPath, cfg.contentWeight,
            )
            ?: SimpleTextMeasurer(
                textSizePx = cfg.textSizePx,
                letterSpacingPx = cfg.letterSpacingPx,
                descent = cfg.textSizePx * 0.2f,
            )
        // 标题独立度量器：对应 app 端 titlePaint（字号 = textSize + titleSize），
        // 与绘制侧 ReaderDrawStyle.titleStyle 同一字号与同一字重
        val titleMeasurer = TextMeasurerProviders
            .createOrNull(
                cfg.titleSizePx, cfg.letterSpacingPx, cfg.textFontPath, cfg.titleWeight,
            )
            ?: SimpleTextMeasurer(
                textSizePx = cfg.titleSizePx,
                letterSpacingPx = cfg.letterSpacingPx,
                descent = cfg.titleSizePx * 0.2f,
            )
        return SimpleChapterLayout(
            measurer = measurer,
            visibleWidth = cfg.visibleWidth,
            visibleHeight = cfg.visibleHeight,
            paddingLeft = cfg.paddingLeft,
            paddingTop = cfg.paddingTop,
            // 真实字体高度（descent - ascent + leading，与 app 端 `TextPaint.textHeight` 同口径），
            // 行距 = textHeight * lineSpacingExtra
            textHeight = measurer.descent - measurer.ascent + measurer.leading,
            descent = measurer.descent,
            lineSpacingExtra = cfg.lineSpacingExtra,
            paragraphSpacing = cfg.paragraphSpacing,
            titleTopSpacing = cfg.titleTopSpacing,
            titleBottomSpacing = cfg.titleBottomSpacing,
            endPadding = cfg.endPadding,
            paragraphIndent = cfg.paragraphIndent,
            textFullJustify = cfg.textFullJustify,
            viewWidth = cfg.viewWidth,
            textBottomJustify = cfg.textBottomJustify,
            doublePage = cfg.doublePage,
            // 几何缩进宽度：对应 app 端 indentCharWidth = getDesiredWidth(paragraphIndent) / 长度
            indentCharWidth = cfg.paragraphIndent.takeIf { it.isNotEmpty() }?.let {
                measurer.measureWidth(it) / it.length
            } ?: 0f,
            indentChar = "　",
            titleMode = cfg.titleMode,
            titleMeasurer = titleMeasurer,
            titleTextHeight = titleMeasurer.descent - titleMeasurer.ascent + titleMeasurer.leading,
            titleDescent = titleMeasurer.descent,
            reviewChar = "▨",
            srcReplaceChar = ChapterContentParserShared.srcReplaceChar,
            layoutCache = paragraphLayoutCache,
            contentWeight = cfg.contentWeight,
            titleWeight = cfg.titleWeight,
        )
    }

    /**
     * 取消窗口外尚未完成的段评 IO；已完成结果保留供同一本书回翻复用。
     * 对照 app ReadBook.clearExpiredChapterLoadingJob 中 reviewCountDeferred 分支。
     */
    private fun clearExpiredReviewCount(clearAll: Boolean = false) {
        val expired = arrayListOf<Deferred<Map<Int, Int>?>>()
        synchronized(syncLock) {
            val iterator = reviewCountDeferred.iterator()
            while (iterator.hasNext()) {
                val (index, deferred) = iterator.next()
                if (clearAll ||
                    (!deferred.isCompleted &&
                        !isInChapterWindow(index, readBook.durChapterIndex.value))
                ) {
                    expired.add(deferred)
                    iterator.remove()
                }
            }
        }
        for (i in expired.indices) expired[i].cancel()
    }

    /**
     * 当前章排版结果写入页状态流并按 durChapterPos 归位页码
     * （对照 app 端 contentLoadFinish 的 containPos 定位；toLast 时自然落到 pages.lastIndex）。
     */
    private fun applyCurChapterPages(
        textChapter: TextChapterShared,
        // 滚动切章 (moveToNext/PrevChapter 滚动路径) 传 false: 保留 offset 连续折算,
        // 对照原版 moveToNextChapter(toFirst=false) 不 resetPageOffset;
        // 菜单切章/重排/刷新等场景默认 true 归零
        resetOffset: Boolean = true,
    ) {
        pageList.clear()
        pageList.addAll(textChapter.pages)
        if (resetOffset) {
            // 切章/重排/刷新后滚动偏移归零 (对照旧 upContent(resetPageOffset=true) → resetPageOffset)
            resetScrollOffset()
        }
        // 跨章朗读起点: 目标章排版完成即触发 (对照原版 openChapter success 回调时机)
        pendingReadAloudStart?.let { startPos ->
            pendingReadAloudStart = null
            readBook.readAloud(startPos = startPos)
        }
        // toLast 且上一章未预载时的 Int.MAX_VALUE 哨兵：落到末页后归一为该页页首
        if (readBook.durChapterPos.value == Int.MAX_VALUE) {
            readBook.updateDurChapterPos(textChapter.lastReadLength)
        }
        syncPageFlows()
        // 对照 app 端 contentLoadFinish → curPageChanged → preDownload
        preDownload()
    }

    /**
     * 按 durChapterPos 反算 pageIndex 并刷新三个页面状态流。
     *
     * 对照原版 TextPageFactory：
     * - [ReadBookShared.msg] 非空时三页流全部显示消息页（原版 curPage/prevPage/nextPage 的 msg 优先分支）
     * - 当前章未装载时当前页给"加载数据中…"占位（原版无章时的 `TextPage()` 默认文案），
     *   prev/next 按相邻章兜底（原版 prevChapter/nextChapter 分支）
     * - 章内翻页时 prev/next 取 pageList 相邻页（原版 curPage±1）
     */
    private fun syncPageFlows() {
        // 阅读消息页优先（原版 msg 检查在所有页面工厂分支之前）
        readBook.msg?.let { msg ->
            val page = buildMessagePage(msg, readBook.durChapterIndex.value, readBook.chapterSize)
            _curTextPage.value = page
            _prevTextPage.value = page
            _nextTextPage.value = page
            _nextPlusTextPage.value = page
            return
        }
        val durIndex = readBook.durChapterIndex.value
        val curChapter = readBook.curTextChapter.value
        if (curChapter == null) {
            // 当前章尚未装载（打开书籍/切章加载中）：当前页显示"加载数据中…"占位。
            // 此时 pageList 可能残留上一章排版（切章未装载分支不清空），不能用于当前页推导
            _curTextPage.value = loadingPlaceholderPage(durIndex)
            val prevChapter = readBook.prevTextChapter.value
            val nextChapter = readBook.nextTextChapter.value
            _prevTextPage.value = prevChapter?.pages?.lastOrNull()
                ?: if (canMoveToPrevChapter()) {
                    loadingPlaceholderPage(durIndex - 1)
                } else {
                    null
                }
            _nextTextPage.value = nextChapter?.pages?.firstOrNull()
                ?: if (canMoveToNextChapter()) {
                    loadingPlaceholderPage(durIndex + 1)
                } else {
                    null
                }
            _nextPlusTextPage.value = nextChapter?.pages?.getOrNull(1)
                ?: if (canMoveToNextChapter()) {
                    loadingPlaceholderPage(durIndex + 1)
                } else {
                    null
                }
            return
        }
        pageIndex = readBook.durPageIndexValue.coerceIn(0, (pageList.size - 1).coerceAtLeast(0))
        _curTextPage.value = pageList.getOrNull(pageIndex)
        // 章首/章末的相邻页跨章节取：prevPage = 上一章末页（prevChapter.lastPage），
        // nextPage = 下一章首页（nextChapter.getPage(0)）；相邻章尚未装载时给
        // "加载数据中…"占位页（原版 loadingTextPage）
        // 修复翻页动画：prev/next 流为 null 时 ReadViewComposable 的 prevContent/nextContent
        // lambda 什么都不渲染，滑入层只有阴影扫过、页面内容不跟着动（原版三页流永远有值）
        val prevChapter = readBook.prevTextChapter.value
        val nextChapter = readBook.nextTextChapter.value
        _prevTextPage.value = pageList.getOrNull(pageIndex - 1)
            ?: prevChapter?.pages?.lastOrNull()
                ?: if (pageIndex == 0 && canMoveToPrevChapter()) {
                loadingPlaceholderPage(durIndex - 1)
            } else {
                null
            }
        _nextTextPage.value = pageList.getOrNull(pageIndex + 1)
            ?: nextChapter?.pages?.firstOrNull()
                ?: if (pageIndex == pageList.lastIndex && canMoveToNextChapter()) {
                loadingPlaceholderPage(durIndex + 1)
            } else {
                null
            }
        // 第 3 页（对照原版 nextPlusPage）：当前页 +2；跨章时取下一章第 1/2 页
        // （pageIndex=lastIndex-1 → 下一章第 0 页；pageIndex=lastIndex → 下一章第 1 页）
        _nextPlusTextPage.value = pageList.getOrNull(pageIndex + 2)
            ?: nextChapter?.pages?.getOrNull(pageIndex + 2 - pageList.size)
                ?: if (pageIndex >= pageList.size - 2 && canMoveToNextChapter()) {
                loadingPlaceholderPage(durIndex + 1)
            } else {
                null
            }
    }

    /**
     * 阅读状态变化后的视图刷新入口（对照原版 `ReadBook.CallBack.upContent`）。
     *
     * [ReadBookShared.upMsg] / [ReadBookShared.initData] 等状态变更经回调触发本方法，
     * 重新推导三页流：消息页（更新目录/错误提示）与"加载数据中…"占位页在此呈现。
     */
    fun onUpContent() {
        syncPageFlows()
    }

    /**
     * "加载数据中…"占位页（对照原版 TextPageFactory.loadingTextPage，文案同 R.string.data_loading）。
     * 当前章未装载时填充当前页流，相邻章尚未装载时填充 prev/next 流，
     * 保证翻页动画滑入层始终有内容。
     */
    private fun loadingPlaceholderPage(chapterIndex: Int): TextPage = placeholderPage(
        msg = appString(AppStringKey.data_loading),
        chapterIndex = chapterIndex,
        chapterSize = readBook.chapterSize,
        title = appString(AppStringKey.data_loading),
    )

    /** 展示占位提示章（原版错误文案同样经 contentLoadFinish 成章展示） */
    private fun showMessageChapter(
        msg: String,
        chapterIndex: Int,
        chapterSize: Int,
        title: String? = "提示",
    ) {
        val page = placeholderPage(msg, chapterIndex, chapterSize, title)
        val textChapter = TextChapterShared(chapterIndex, listOf(page))
        page.textChapter = textChapter
        readBook.updateTextChapter(0, textChapter)
        applyCurChapterPages(textChapter)
    }

    // region 预下载 / 目录自动更新（对照 app 端 ReadBook.preDownload / upToc）
    /** 预下载前后章节（实现已收敛至 [ChapterPreDownloader]，与漫画模式共用一份）。 */
    private fun preDownload() {
        preDownloader.preDownload()
    }

    /**
     * 取消预下载（对照 app 端 ReadBook.cancelPreDownloadTask，正文加载完成后由平台侧调用）。
     *
     * 不带原版的"当前章已装载"守卫：该守卫会让"停在最后一章"或"下一章加载失败"时
     * 离开阅读页也不取消，预下载继续跑到跑完（口径同漫画侧）。
     */
    fun cancelPreDownloadTask() {
        preDownloader.cancel()
    }

    /**
     * 阅读中自动更新目录（实现已收敛至 [ChapterTocUpdater]，四模式共用一份）。
     *
     * @param force 跳过 canUpdate / 剩余章数 / 节流三重守卫（预下载遇到"目录里查不到该章"时用）
     */
    fun upToc(force: Boolean = false) {
        val book = readBook.book.value ?: return
        tocUpdater.upToc(
            book = book,
            bookSource = readBook.bookSource.value,
            chapterSize = readBook.chapterSize,
            durChapterIndex = readBook.durChapterIndex.value,
            force = force,
        )
    }

    // endregion

    /**
     * 整书目录重新解析（对照 app 端 READ 菜单"更新目录"：
     * `book.getHandler().clear()` + epub 清缓存 + loadChapterList）。
     *
     * 走 [loadChapterListFromSource] 回源重拉目录（本地书重解析文件），成功后
     * 清正文/排版缓存并重载三章滑窗；失败（目录为空）保持现状不破坏内存目录。
     */
    fun updateToc() {
        val book = readBook.book.value ?: return
        // 对照原版菜单"更新目录" → loadChapterList：整页显示"更新目录中…"
        readBook.upMsg(appString(AppStringKey.toc_updateing))
        scope.launch(IoDispatcher) {
            // 本地 txt 解析句柄缓存清空 (对照原版 UPDATE_TOC: it.getHandler().clear())
            FileBookProviders.get().getHandler(book).clear()
            if (book.isEpub) {
                BookStorageProviders.get().clearCache(book)
            }
            val list = loadChapterListFromSource(book)
            if (list.isEmpty()) {
                // 失败（目录为空）保持现状不破坏内存目录；消息页显示加载失败（对照原版 error_load_toc）
                readBook.upMsg(appString(AppStringKey.error_load_toc))
                return@launch
            }
            readBook.updateChapterList(list)
            processedContentCache.clear()
            readBook.clearTextChapter()
            readBook.upMsg(null)
            val index = readBook.durChapterIndex.value
            // 目录重解析/清整书缓存后整窗重载: 不受 preDownloadNum 门控 (缓存已清空,
            // 前后章留着旧排版会与新目录/新缓存不一致)
            loadChapterWindow(index, includeNeighbors = true)
        }
    }

    /**
     * 清空整书缓存并重载三章滑窗（对照 app 端 refreshContentAll：
     * BookHelp.clearCache + ReadBook.loadContent）。
     *
     * 供"去除 ruby/h 标签"等全章生效的配置切换使用，保证滑窗内所有章节
     * 都按新配置重新处理，而不是只重排当前章。
     */
    fun refreshContentAll() {
        val book = readBook.book.value ?: return
        scope.launch(IoDispatcher) {
            BookStorageProviders.get().clearCache(book)
            processedContentCache.clear()
            readBook.clearTextChapter()
            val index = readBook.durChapterIndex.value
            // 目录重解析/清整书缓存后整窗重载: 不受 preDownloadNum 门控 (缓存已清空,
            // 前后章留着旧排版会与新目录/新缓存不一致)
            loadChapterWindow(index, includeNeighbors = true)
        }
    }

    // ===== 阅读菜单配置开关 (对照 app 端 ReadMenu.onTopMenuAction 各分支, iOS/鸿蒙共用) =====

    /**
     * 翻转替换规则开关 (对照 app 端 ENABLE_REPLACE 分支：changeReplaceRuleState)。
     * book.config.useReplaceRule 取反 → 落库 → 刷新替换规则缓存 → 同章重载保留进度。
     */
    fun toggleUseReplaceRule() {
        val book = readBook.book.value ?: return
        book.config.useReplaceRule = !book.getUseReplaceRule()
        scope.launch {
            ContentProcessorProviders.get().upReplaceRules()
            // 只 PATCH 阅读配置列; 整行 update 会冲掉后台 updateToc 写入的目录/元数据
            AppDbProviders.get().bookDao.updateReadConfig(book.bookUrl, book.config)
            loadChapter(readBook.durChapterIndex.value)
        }
    }

    /**
     * 翻转重新分段 (对照 app 端 RE_SEGMENT 分支)：reSegment 取反 → 落库 → 同章重载保留进度。
     */
    fun toggleReSegment() {
        val book = readBook.book.value ?: return
        book.config.reSegment = !book.config.reSegment
        scope.launch {
            // 只 PATCH 阅读配置列; 整行 update 会冲掉后台 updateToc 写入的目录/元数据
            AppDbProviders.get().bookDao.updateReadConfig(book.bookUrl, book.config)
            loadChapter(readBook.durChapterIndex.value)
        }
    }

    /**
     * 翻转去除标签配置并全章清缓存重载 (对照 app 端 DEL_RUBY_TAG/DEL_H_TAG 的 toggleDelTag)。
     *
     * @param tag 标签位掩码 ([Book.rubyTag] / [Book.hTag])
     */
    fun toggleDelTag(tag: Long) {
        val book = readBook.book.value ?: return
        book.config.delTag = if (book.config.delTag and tag == tag) {
            book.config.delTag and tag.inv()
        } else {
            book.config.delTag or tag
        }
        scope.launch {
            // 只 PATCH 阅读配置列; 整行 update 会冲掉后台 updateToc 写入的目录/元数据
            AppDbProviders.get().bookDao.updateReadConfig(book.bookUrl, book.config)
            refreshContentAll()
        }
    }

    /**
     * 设置书籍文本编码 (对照 app 端 `ReadBook.setCharset`): 写 book.charset
     * 并发 [ReadBookEvents.postLoadChapterList] 触发目录重载, 按新编码重新解析。
     * 供顶栏"设置编码"菜单动作使用 (必须走本实例, app 端 ReadBook 单例与阅读器非同一实例)。
     */
    fun setCharset(charset: String) = readBook.setCharset(charset)

    /**
     * 手动同步云进度（对照 app 端 BaseReadViewModel.syncProgress, manual=true）。
     *
     * 与 [pullCloudProgress] 同一套三路比对：云端无/较旧 → 上传（成功后回调
     * [uploadSuccessAction]）；云端较新 → 发确认事件由 UI 弹窗；相等 → 回调
     * [syncSuccessAction]。手动路径与自动拉取共用 progressSyncScope。
     */
    fun syncProgressManual(uploadSuccessAction: () -> Unit, syncSuccessAction: () -> Unit) {
        val book = readBook.book.value ?: return
        progressSyncScope.launch {
            saveProgressAwait()
            AppWebDavShared.syncProgress(
                book = book,
                manual = true,
                onNewProgress = { ReadBookEvents.postConfirmNewProgress(it) },
                onUploadSuccess = uploadSuccessAction,
                onSyncEqual = syncSuccessAction,
            )
        }
    }

    /**
     * 构造占位 [TextPage]（章节越界 / 缓存未命中的兜底页），排版为居中消息行。
     *
     * @param msg 显示文本
     * @param chapterIndex 章节序号
     * @param chapterSize 章节总数
     * @param title 章节标题（可选，默认 "提示"）
     */
    private fun placeholderPage(
        msg: String,
        chapterIndex: Int,
        chapterSize: Int,
        title: String? = "提示",
    ): TextPage = buildMessagePage(
        msg = msg,
        chapterIndex = chapterIndex,
        chapterSize = chapterSize,
        title = title,
    )

    /**
     * 构造消息占位页并排版为居中行（对照原版 `TextPage.format()` 消息页分支：
     * StaticLayout 按可见宽度换行，文本块垂直/水平居中，逐字生成 TextColumn）。
     *
     * 排版参数取当前 [_layoutConfig]；视口未注入（<=0）或文案为空时仅构造无行占位页，
     * 由后续 [updateLayoutConfig] 重建。
     */
    private fun buildMessagePage(
        msg: String,
        chapterIndex: Int,
        chapterSize: Int,
        title: String? = null,
    ): TextPage {
        val cfg = _layoutConfig.value
        val page = TextPage(
            text = msg,
            title = title ?: "",
            chapterIndex = chapterIndex,
            chapterSize = chapterSize,
        ).apply {
            isMsgPage = true
        }
        if (cfg.visibleWidth <= 0 || cfg.visibleHeight <= 0 || msg.isEmpty()) return page
        val measurer = TextMeasurerProviders
            .createOrNull(
                cfg.textSizePx, cfg.letterSpacingPx, cfg.textFontPath, cfg.contentWeight,
            )
            ?: SimpleTextMeasurer(
                textSizePx = cfg.textSizePx,
                letterSpacingPx = cfg.letterSpacingPx,
                descent = cfg.textSizePx * 0.2f,
            )
        val textHeight = measurer.descent - measurer.ascent + measurer.leading
        val lineSpacing = textHeight * cfg.lineSpacingExtra
        // 贪心换行：按 \n 分段，逐字累积宽度，超出可见宽度断行（消息文案短，足够）。
        // 同时记下每行首字符在 msg 内的偏移：折行不增删字符，段间隔一个 \n，
        // 因此行文本拼回来即 page.text（口径同 PaginationEngine 的 pagePosition）
        val wrappedLines = arrayListOf<String>()
        val wrappedOffsets = arrayListOf<Int>()
        var segmentStart = 0
        for (segment in msg.split('\n')) {
            if (segment.isEmpty()) {
                wrappedLines.add("")
                wrappedOffsets.add(segmentStart)
                segmentStart++
                continue
            }
            val widths = FloatArray(segment.length)
            measurer.measureGlyphWidths(segment, widths)
            val sb = StringBuilder()
            var pieceStart = 0
            var lineWidth = 0f
            for (i in segment.indices) {
                if (sb.isNotEmpty() && lineWidth + widths[i] > cfg.visibleWidth) {
                    wrappedLines.add(sb.toString())
                    wrappedOffsets.add(segmentStart + pieceStart)
                    sb.setLength(0)
                    pieceStart = i
                    lineWidth = 0f
                }
                sb.append(segment[i])
                lineWidth += widths[i]
            }
            wrappedLines.add(sb.toString())
            wrappedOffsets.add(segmentStart + pieceStart)
            segmentStart += segment.length + 1
        }
        // 文本块总高（行盒高 × 行数），垂直居中于可视区（对照原版 format 的
        // y = (visibleHeight - layout.height) / 2）；行水平居中（x = paddingLeft +
        // (visibleWidth - lineWidth) / 2）
        val totalHeight = wrappedLines.size * lineSpacing
        var y = cfg.paddingTop + (cfg.visibleHeight - totalHeight) / 2f
        if (y < cfg.paddingTop) y = cfg.paddingTop.toFloat()
        for ((lineIndex, lineText) in wrappedLines.withIndex()) {
            val textLine = TextLine(text = lineText)
            textLine.lineTop = y
            textLine.lineBottom = y + textHeight
            // baseline = lineTop - ascent（ascent 为负，同正文行 lineBase = lineBottom - descent）
            textLine.lineBase = y - measurer.ascent
            // 字符坐标：占位页无章节基址，chapterPosition 与 pagePosition 同值
            textLine.pagePosition = wrappedOffsets[lineIndex]
            textLine.chapterPosition = wrappedOffsets[lineIndex]
            // 列宽度与本行换行测量同源（measureGlyphWidths），保证绘制不走样
            val widths = FloatArray(lineText.length)
            measurer.measureGlyphWidths(lineText, widths)
            val lineWidth = widths.sum()
            var x = cfg.paddingLeft + (cfg.visibleWidth - lineWidth) / 2f
            if (x < cfg.paddingLeft) x = cfg.paddingLeft.toFloat()
            for (i in lineText.indices) {
                val char = lineText[i].toString()
                val cw = widths[i]
                textLine.addColumn(TextColumn(start = x, end = x + cw, char))
                x += cw
            }
            page.addLine(textLine)
            y += lineSpacing
        }
        // 几何参数注入（滚动模式视口裁剪/行距对齐用，对照排版层 onPageCompleted 注入）
        page.paddingTop = cfg.paddingTop
        page.visibleHeight = cfg.visibleHeight
        page.visibleBottom = cfg.paddingTop + cfg.visibleHeight
        page.lineSpacingExtra = cfg.lineSpacingExtra
        page.contentPaintTextHeight = textHeight
        page.height = cfg.visibleHeight.toFloat()
        page.upRenderHeight()
        return page
    }

    /**
     * 排版几何 / 字号配置。
     *
     * UI 层按窗口视口 + ReadBookConfig 构造后调 [updateLayoutConfig] 推入；默认 [DEFAULT]
     * 为桌面 720x1080 等价近似值，仅作无注入时的兜底。
     *
     * @param viewWidth 视图总宽（含 padding，px）
     * @param viewHeight 视图总高（含 padding，px）
     * @param paddingLeft/Top/Right/Bottom 内边距（px，对应 `ReadBookConfig.paddingXxx` dp 折算）
     * @param textSizePx 文字大小（px，对应 app 端 `ChapterProvider.contentPaint.textSize`）
     * @param titleSizePx 标题字号（px，= `(textSize + titleSize)` sp 折算；
     *   [SimpleChapterLayout] 用独立标题度量器按本字段度量并排版）
     * @param letterSpacingPx 字间距（px，= `ReadBookConfig.letterSpacing * textSizePx`）
     * @param lineSpacingExtra 行高乘数（= `ReadBookConfig.lineSpacingExtra / 10`）
     * @param textBold 字重档位（= `ReadBookConfig.textBold`），经 [contentWeight] / [titleWeight]
     *   决定度量器取哪一面字重
     * @param paragraphSpacing 段间距（对应 app 端 `ChapterProvider.paragraphSpacing`）
     * @param titleTopSpacing 标题顶部留白（px）
     * @param titleBottomSpacing 标题底部留白（px）
     * @param paragraphIndent 段落缩进字符串（默认全角空格 `　　`）
     * @param textFullJustify 是否两端对齐
     * @param textBottomJustify 是否底部对齐
     * @param titleMode 标题位置 0:居左 1:居中 2:隐藏（= `ReadBookConfig.titleMode`）
     * @param textFontPath 自定义正文字体文件路径（= `ReadBookConfig.textFont`，空 = 平台默认字体）；
     *   度量侧必须与绘制侧 `loadReaderFontFamily` 用同一个文件，否则选字体后正文错位
     */
    /** 已处理的章节内容缓存（跳过下载/ContentProcessor/解析，只重排版） */
    private data class ProcessedChapterContent(
        val chapter: BookChapter,
        val displayTitle: String,
        val textList: List<String>,
        val parsedParagraphs: List<ParsedParagraph>,
        val effectiveReplaceRules: List<ReplaceRule>?,
        val reviewCountMap: Map<Int, Int>?,
        val imageResolver: ImageResolver?,
        val sameTitleRemoved: Boolean = false,
    )

    data class LayoutConfig(
        val viewWidth: Int = 720,
        val viewHeight: Int = 1080,
        val paddingLeft: Int = 44,
        val paddingTop: Int = 10,
        val paddingRight: Int = 44,
        val paddingBottom: Int = 8,
        val textSizePx: Float = 48f,
        val titleSizePx: Float = 60f,
        val letterSpacingPx: Float = 0f,
        // 行高乘数 / 段距与 ReadStyleConfig 默认值（已内聚的内置「微信读书」）同口径：
        // lineSpacingExtra = 10/10，paragraphSpacing = 6
        val lineSpacingExtra: Float = 1.0f,
        val paragraphSpacing: Int = 6,
        val titleTopSpacing: Int = 0,
        val titleBottomSpacing: Int = 0,
        val paragraphIndent: String = "　　",
        val textFullJustify: Boolean = true,
        // 默认值与 ReadBookConfig 一致（textBottomJustify=true / titleMode=0）
        val textBottomJustify: Boolean = true,
        /** 末页底部留白 px（原版为 20dp；DEFAULT 按 2x 密度折算 40px） */
        val endPadding: Int = 40,
        val titleMode: Int = 0,
        val textFontPath: String = "",
        /** 双页排版（对照 app 端 ChapterProvider.doublePage；true 时按半宽分栏排版） */
        val doublePage: Boolean = false,
        /**
         * 字重档位（= `ReadBookConfig.textBold`，0 正常 / 1 粗体 / 2 细体）。
         * 度量器必须按绘制侧同一字重取字形，否则「粗体绘制 / 常规度量」会让行尾溢出。
         */
        val textBold: Int = 0,
    ) {
        /** 正文字重（100..900），与绘制侧 ReaderDrawStyle 同源 [ReaderFontWeights]。 */
        val contentWeight: Int get() = ReaderFontWeights.content(textBold)

        /** 标题字重（100..900），与绘制侧 ReaderDrawStyle 同源 [ReaderFontWeights]。 */
        val titleWeight: Int get() = ReaderFontWeights.title(textBold)

        /** 可视区宽度（px，扣除左右内边距；双页模式为单栏宽 = 全宽/2 - 左右内边距） */
        val visibleWidth: Int
            get() = if (doublePage) {
                viewWidth / 2 - paddingLeft - paddingRight
            } else {
                viewWidth - paddingLeft - paddingRight
            }

        /** 可视区高度（px，扣除上下内边距） */
        val visibleHeight: Int get() = viewHeight - paddingTop - paddingBottom

        companion object {
            /**
             * 无注入时的兜底配置：桌面 720x1080 视口，其余参数按 2x 密度折算自
             * [io.legado.app.help.config.ReadStyleConfig] 的默认值（已内聚的内置「微信读书」）：
             * 正文 24sp→48px、标题 (24+4+2)sp→60px、字距 0、边距 22/5/22/4dp→正整数 px。
             * 真实阅读页一律走 `ReaderRoute.buildLayoutConfig` 的实测视口，不走本值。
             */
            val DEFAULT = LayoutConfig()
        }
    }
}

/**
 * 搜索结果定位信息 (KMP 共享)。
 *
 * 原 app 端 `ReadBookViewModel.SearchPosition` 下沉至 commonMain,
 * 供 app 端 ReadBookViewModel 及桌面端共享 [searchResultPositions] 算法产物。
 * 纯数据类, 无 Android 依赖。
 */
data class SearchPosition(
    val pageIndex: Int,
    val lineIndex: Int,
    val charIndex: Int,
    val addLine: Int,
    val charIndex2: Int
)

/**
 * 内容搜索跳转定位算法 (KMP 共享)。
 *
 * 原 app 端 `ReadBookViewModel.searchResultPositions` 纯算法下沉:
 * 根据 [searchResult] 在章节正文中第 N 次出现的位置, 反推其所在页 / 行 / 字符偏移,
 * 并处理跨行 / 跨页修正。零 Android 依赖, 外部依赖全部参数化:
 * - `textChapter.pages` → [pages]
 * - `textChapter.getContent()` → [content]
 * - `searchContentQuery` (ViewModel 字段) → [query]
 *
 * 实现逻辑与 app 端原方法完全一致, 仅做位置迁移与参数化, 未改变任何计算步骤。
 *
 * @param pages 章节已排版的页列表 (对应 TextChapter.pages)
 * @param content 章节正文全文 (对应 TextChapter.getContent())
 * @param query 搜索关键字 (对应 ReadBookViewModel.searchContentQuery)
 * @param searchResult 单个搜索结果 (含 resultCountWithinChapter 用于定位第 N 次出现)
 * @return 计算出的 [SearchPosition]
 */
fun searchResultPositions(
    pages: List<TextPage>,
    content: String,
    query: String,
    searchResult: SearchResult
): SearchPosition {
    // calculate search result's pageIndex
    val queryLength = query.length

    var count = 0
    var index = content.indexOf(query)
    while (count != searchResult.resultCountWithinChapter) {
        index = content.indexOf(query, index + queryLength)
        count += 1
    }
    val contentPosition = index
    var pageIndex = 0
    var length = pages[pageIndex].text.length
    while (length < contentPosition && pageIndex + 1 < pages.size) {
        pageIndex += 1
        length += pages[pageIndex].text.length
    }

    // calculate search result's lineIndex
    val currentPage = pages[pageIndex]
    val curTextLines = currentPage.lines
    var lineIndex = 0
    var curLine = curTextLines[lineIndex]
    length = length - currentPage.text.length + curLine.text.length
    if (curLine.isParagraphEnd) length++
    while (length <= contentPosition && lineIndex + 1 < curTextLines.size) {
        lineIndex += 1
        curLine = curTextLines[lineIndex]
        length += curLine.text.length
        if (curLine.isParagraphEnd) length++
    }

    // charIndex
    val currentLine = currentPage.lines[lineIndex]
    var curLineLength = currentLine.text.length
    if (currentLine.isParagraphEnd) curLineLength++
    length -= curLineLength

    val charIndex = contentPosition - length
    var addLine = 0
    var charIndex2 = 0
    // change line
    if ((charIndex + queryLength) > curLineLength) {
        addLine = 1
        charIndex2 = charIndex + queryLength - curLineLength - 1
    }
    // changePage
    if ((lineIndex + addLine + 1) > currentPage.lines.size) {
        addLine = -1
        charIndex2 = charIndex + queryLength - curLineLength - 1
    }
    return SearchPosition(pageIndex, lineIndex, charIndex, addLine, charIndex2)
}

/**
 * 回源拉取目录 (提取自 [ReadBookViewModelShared.loadChapterListFromSource], 供音频播放 /
 * RSS 阅读等下沉模块复用, 消除"目录缺失时只读 DB → 内容加载静默失败"的同类回归):
 *
 * - 本地书: FileBook.getChapterList (重解析文件)
 * - 网络书: WebBook.getChapterListAwait (书源目录规则; [runPerJs] 对照原版 loadChapterList
 *   的 runPreUpdateJs 参数, 文本阅读场景 true, RSS 未入架书 false)
 * - 书架书落库 (对照原版 inBookshelf 守卫; runPreUpdateJs 改 bookUrl 时按新 url 迁移)
 * - 失败返回 emptyList 并记录日志 (不抛异常, 调用方自行决定错误展示)
 */
internal suspend fun fetchChapterListFromSource(
    book: Book,
    source: BookSource?,
    runPerJs: Boolean = true,
): List<BookChapter> {
    return try {
        BookChapterLoader.fetchFromSource(book, source, runPerJs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}
