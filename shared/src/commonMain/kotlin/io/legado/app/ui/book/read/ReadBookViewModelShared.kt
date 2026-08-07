package io.legado.app.ui.book.read

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.BookHelpShared
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.addType
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.CacheBookShared
import io.legado.app.model.ReadBookPlatforms
import io.legado.app.model.ReadBookShared
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.FileBookProviders
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.read.ReadBookViewModelShared.LayoutConfig.Companion.DEFAULT
import io.legado.app.ui.book.read.page.PageDelegateShared
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterContentParserShared
import io.legado.app.ui.book.read.page.provider.ImageResolver
import io.legado.app.ui.book.read.page.provider.ImageResolverProviders
import io.legado.app.ui.book.read.page.provider.ParsedParagraph
import io.legado.app.ui.book.read.page.provider.SimpleChapterLayout
import io.legado.app.ui.book.read.page.provider.SimpleTextMeasurer
import io.legado.app.ui.book.read.page.provider.TextMeasurerProviders
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.utils.postEvent
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlin.math.min

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
     * 页内容原地变更版本号（朗读高亮等对 TextPage/TextLine 的原地修改）。
     *
     * 修改不改变 data class 相等性，StateFlow 去重后不会重发，Compose 侧 Canvas
     * 不会重绘。订阅者（PageViewComposable/PageContentCanvas）消费本值强制重绘，
     * 对照 app 端 `upContent` 后的 invalidate 路径。
     */
    private val _pageContentVersion = MutableStateFlow(0)
    val pageContentVersion: StateFlow<Int> get() = _pageContentVersion

    /** 页内容原地变更后自增，触发 Compose 阅读页重绘（朗读高亮/清除）。 */
    fun bumpPageContentVersion() {
        _pageContentVersion.value++
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
    private val loadingChapters = arrayListOf<Int>()
    /** 单章排版/加载任务；切章时取消三章窗口外任务，对齐 app chapterLoadingJobs。 */
    private val chapterLoadingJobs = mutableMapOf<Int, Job>()

    // 替代原 @Synchronized 的 this 监视器 (kotlin.jvm.Synchronized 无 common 变体且 native 无效)
    private val syncLock = SynchronizedObject()
    private var preDownloadTask: Job? = null
    private val downloadedChapters = mutableSetOf<Int>()
    private val downloadFailChapters = mutableMapOf<Int, Int>()
    private val downloadScope = CoroutineScope(SupervisorJob() + IoDispatcher)
    private val preDownloadSemaphore = Semaphore(2)

    /** 段评数按 chapter.index 复用；与 app ReadBook.reviewCountDeferred 生命周期一致。 */
    private val reviewCountDeferred = mutableMapOf<Int, Deferred<Map<Int, Int>?>>()
    private var reviewCountBookUrl: String? = null

    // 缓存已处理的章节内容，视口变化时只重排版不重新下载/处理
    private val processedContentCache = mutableMapOf<Int, ProcessedChapterContent>()
    private var processedContentBookUrl: String? = null
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
     * 桌面端 TocDrawerContent 用其高亮当前章节 + 跳转后自动滚动定位。
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
    // endregion

    /**
     * 跳转到指定页 (对照 app 端 `ReadBook.skipToPage`)。
     * 进度条 page 模式松手后按页索引跳转, 跨章时由排版层自动接续。
     */
    fun skipToPage(index: Int) {
        resetScrollOffset()
        readBook.skipToPage(index)
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
            runCatching {
                val chapter = AppDbProviders.get().bookChapterDao
                    .getChapter(book.bookUrl, index) ?: return@launch
                chapter.title = newTitle
                AppDbProviders.get().bookChapterDao.update(chapter)
                // 内存目录同步替换 (data class 新实例, 不影响其他字段引用)
                readBook.chapterListValue = readBook.chapterListValue?.map { c ->
                    if (c.index == index) chapter else c
                }
            }.onFailure {
                AppLog.put("重命名章节失败\n${it.message}", it)
            }
            ReadBookEvents.postMenuRefresh()
            loadContent(index)
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

    init {
        // 朗读宿主等非 Compose 消费者经 ActiveReadBookRegistry 取当前阅读 ViewModel
        ActiveReadBookRegistry.attachViewModel(this)
    }

    /**
     * 推入新排版配置并按新参数重排（对照原版 `ChapterProvider.upStyle` / `upViewSize` 后
     * 发 LOAD_CONTENT → `ReadBook.loadContent(resetPageOffset = false)`）。
     * 视口无效或配置未变时不重排。
     */
    fun updateLayoutConfig(config: LayoutConfig) {
        if (config.visibleWidth <= 0 || config.visibleHeight <= 0) return
        if (_layoutConfig.value == config) return
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
        for (i in intArrayOf(index, index + 1, index - 1)) {
            val cached = processedContentCache[i]
            if (cached != null) {
                // 复用已处理内容，只重排版
                launchChapterLoad(i) {
                    removeLoading(i)
                    relayoutFromCache(i, cached)
                }
            } else {
                launchChapterLoad(i) {
                    removeLoading(i)
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
        launchChapterLoad(index) {
            val chapter = runCatching {
                AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, index)
            }.getOrNull()
            if (chapter != null) {
                runCatching { BookStorageProviders.get().delContent(book, chapter) }
            }
            readBook.clearTextChapter()
            removeLoading(index)
            loadContent(index)
        }
    }

    /**
     * 打开章节（对照 app 端 `ReadBook.openChapter` + `loadContent(resetPageOffset)`）：
     * 读章节列表 / 解析书源，跳章时清三章滑窗并重置进度，随后装载当前章并异步预载前后章。
     * 正文经 ContentProcessor 完整处理链后排版，详见 [contentLoadFinish]。
     */
    fun loadChapter(index: Int) {
        val currentBookUrl = readBook.book.value?.bookUrl
        if (reviewCountBookUrl != currentBookUrl) {
            clearExpiredChapterLoadingJobs(clearAll = true)
            reviewCountBookUrl = currentBookUrl
        }
        // 书籍切换时清空已处理内容缓存
        if (processedContentBookUrl != currentBookUrl) {
            processedContentCache.clear()
            processedContentBookUrl = currentBookUrl
        }
        launchChapterLoad(index) {
            // 1. 同步 shared 状态字段（callback 通知）
            readBook.loadChapter(index)

            // 2. 章节列表：内存优先，内存没有再查库。
            // 未加入书架的书按原版语义不落库（BaseReadViewModel.loadChapterList 的 inBookshelf 守卫），
            // 章节只存在于内存，无条件用查库结果覆盖会把它清成空目录。
            val book = readBook.book.value ?: return@launchChapterLoad
            var chapterList: List<BookChapter> =
                readBook.chapterList.value.takeIf { it.firstOrNull()?.bookUrl == book.bookUrl }
                    ?: runCatching {
                        AppDbProviders.get().bookChapterDao.getChapterList(book.bookUrl)
                    }.onFailure {
                        AppLog.put("读取目录失败\n${it.message}", it)
                    }.getOrDefault(emptyList()).also { readBook.updateChapterList(it) }

            // 3. 书源解析缓存（对照 app 端 ReadBook.upWebBook，本地书为 null；upToc/预下载复用）
            if (book.isLocal) {
                readBook.updateBookSource(null)
            } else if (readBook.bookSource.value?.bookSourceUrl != book.origin) {
                readBook.updateBookSource(
                    runCatching {
                        AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
                    }.getOrNull()
                )
            }

            if (chapterList.isEmpty()) {
                // 内存和库都没有目录：回源重解析（对照 app 端 BaseReadViewModel.upBook 的
                // `!inBookshelf || totalChapterNum == 0 || 库里查空` → loadChapterList 分支）
                chapterList = loadChapterListFromSource(book)
            }

            if (chapterList.getOrNull(index) == null) {
                // 章节序号越界：显示占位页
                showMessageChapter("无章节内容", index, chapterList.size)
                return@launchChapterLoad
            }

            // 3.5 打开书首次装载时拉云进度（对照 app 端 initBook 的 syncProgress，每本书只触发一次）
            if (cloudSyncedBookUrl != book.bookUrl) {
                cloudSyncedBookUrl = book.bookUrl
                pullCloudProgress(book)
            }

            // 4. 跳章（对照 app 端 openChapter）：清滑窗 + 进度归零；同章重载保留 durChapterPos 恢复进度
            if (index != readBook.durChapterIndex.value) {
                readBook.clearTextChapter()
                readBook.updateDurChapterIndex(index)
                readBook.updateDurChapterPos(0)
                clearExpiredChapterLoadingJobs()
            }

            // 5. 当前章优先装载，前后章异步预载（对照 app 端 loadContent 三章同载）
            loadContent(index)
            launchChapterLoad(index + 1) { loadContent(index + 1) }
            launchChapterLoad(index - 1) { loadContent(index - 1) }
        }
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
     * 以 chapter.index 记录任务；同章新任务替换旧任务，完成时仅清理自身。
     */
    private fun launchChapterLoad(index: Int, block: suspend CoroutineScope.() -> Unit): Job {
        synchronized(syncLock) {
            chapterLoadingJobs.remove(index)?.cancel()
        }
        val job: Job = scope.launch(block = block)
        synchronized(syncLock) { chapterLoadingJobs[index] = job }
        job.invokeOnCompletion {
            synchronized(syncLock) {
                if (chapterLoadingJobs[index] === job) chapterLoadingJobs.remove(index)
            }
        }
        return job
    }

    /**
     * 取消当前三章窗口外的排版/正文任务；clearAll 用于切书和销毁。
     */
    private fun clearExpiredChapterLoadingJobs(clearAll: Boolean = false) {
        synchronized(syncLock) {
            val iterator = chapterLoadingJobs.iterator()
            while (iterator.hasNext()) {
                val (index, job) = iterator.next()
                if (clearAll || index !in readBook.durChapterIndex.value - 1..readBook.durChapterIndex.value + 1) {
                    job.cancel()
                    iterator.remove()
                    loadingChapters.remove(index)
                }
            }
        }
        clearExpiredReviewCount(clearAll)
    }

    /**
     * 装载单章正文并按滑窗归位（对照 app 端 `ReadBook.loadContentAwait`）：
     * 缓存未命中经 [downloadAwait] 联网，失败文案与原版一致作为正文排版展示。
     */
    private suspend fun loadContent(index: Int) {
        if (index < 0 || index >= readBook.chapterSize) return
        if (index !in readBook.durChapterIndex.value - 1..readBook.durChapterIndex.value + 1) return
        if (!addLoading(index)) return
        // 当前章未装载：先刷新页面流，展示"加载数据中…"占位（对照原版 moveToNextChapter /
        // openChapter 的 upContent 后 pageFactory 无章兜底页；msg 优先，不覆盖消息页）。
        // 装载完成后由 contentLoadFinish → applyCurChapterPages 替换为正文。
        if (index == readBook.durChapterIndex.value && readBook.msg == null) {
            syncPageFlows()
        }
        try {
            val book = readBook.book.value ?: return
            val chapter = readBook.chapterList.value.getOrNull(index)
                ?: runCatching {
                    AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, index)
                }.getOrNull()
                ?: return
            // 与 app ReadBook.loadContent 一致：正文 IO 前先并行启动段评数请求，正文缓存命中也不阻塞。
            val countDeferred = startReviewCountFetchAsync(book, chapter)
            // 正文缓存读取是同步文件 IO (JvmBookStorage.readAllBytes, MB 级), 必须切 IO 线程
            val cached = runCatching {
                withContext(IoDispatcher) {
                    BookStorageProviders.get().getContent(book, chapter)
                }
            }.getOrNull()
            val content = cached ?: downloadAwait(book, chapter)
            // 原版在 contentLoadFinish 入口先 removeLoading；先释放守卫，确保段评迟到触发的重排
            // 可以立即重新加载同章，不会被本次 finally 尚未执行的 loading 标记挡住。
            removeLoading(index)
            contentLoadFinish(book, chapter, content, countDeferred)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("加载正文出错\n${e.message}", e)
        } finally {
            removeLoading(index)
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
     * 段评数迟于当前章排版到达时触发整章重排；仅当前章且章节对象仍相同时生效。
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
            readBook.clearTextChapter()
            synchronized(syncLock) {
                reviewCountDeferred[chapter.index] = CompletableDeferred(map)
            }
            launchChapterLoad(chapter.index) { loadContent(chapter.index) }
            launchChapterLoad(chapter.index + 1) { loadContent(chapter.index + 1) }
            launchChapterLoad(chapter.index - 1) { loadContent(chapter.index - 1) }
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
        if (chapter.index !in readBook.durChapterIndex.value - 1..readBook.durChapterIndex.value + 1) {
            return
        }
        val processor = ContentProcessorProviders.get()
        val displayTitle = chapter.getDisplayTitle(
            processor.getTitleReplaceRules(book),
            book.getUseReplaceRule(),
        )
        // 原版 ReadBook.processContent 把完整 BookContent 交给 ChapterProvider/TextChapterLayout：
        // textList 的项边界、空行、首尾空白和 HTML 图片标签均属于排版输入，不能先压平后
        // split/trim/filter。这里直接消费同一份 BookContent，并复用下沉的解析器。
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
        when (val offset = chapter.index - readBook.durChapterIndex.value) {
            0 -> {
                readBook.updateTextChapter(offset, textChapter)
                applyCurChapterPages(textChapter)
                scheduleReviewRelayoutIfNeeded(countDeferred, chapter, textChapter)
            }
            -1, 1 -> {
                readBook.updateTextChapter(offset, textChapter)
                // 相邻章装载完成：刷新页面流，替换章边界的"加载数据中…"占位页
                // （对照原版 upContent 后 pageFactory.nextPage/prevPage 立即取到新章页面）
                syncPageFlows()
            }
        }
    }

    /**
     * 复用已缓存的处理结果只重排版（视口大小变化时调用，跳过下载/ContentProcessor/解析/JS执行）。
     * 纯 UI 重排：复用 imageResolver（其内部 sizes 缓存避免重复网络请求），跳过 preDownload。
     */
    private suspend fun relayoutFromCache(index: Int, cached: ProcessedChapterContent) {
        if (index !in readBook.durChapterIndex.value - 1..readBook.durChapterIndex.value + 1) return
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
        when (val offset = index - readBook.durChapterIndex.value) {
            0 -> {
                readBook.updateTextChapter(offset, textChapter)
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

            -1, 1 -> {
                readBook.updateTextChapter(offset, textChapter)
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
            clearExpiredChapterLoadingJobs()
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
                launchChapterLoad(curIndex + 1) { loadContent(curIndex + 1) }
            }
            launchChapterLoad(curIndex + 2) { loadContent(curIndex + 2) }
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
            clearExpiredChapterLoadingJobs()
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
                launchChapterLoad(curIndex - 1) { loadContent(curIndex - 1) }
            }
            launchChapterLoad(curIndex - 2) { loadContent(curIndex - 2) }
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
        runCatching {
            val durChapterIndex = readBook.durChapterIndex.value
            val textChapter = readBook.curTextChapter.value
            // 末页停留时 durChapterPos 取负编码「停在章末」（原版 ReadBook.saveRead:904），
            // 重进时由 ReadBookShared.loadBook 归一还原
            val durChapterPos = readBook.durChapterPos.value *
                (if (textChapter != null && textChapter.isLastIndex(readBook.durPageIndexValue)) -1 else 1)
            // durChapterTitle 过 titleReplaceRules（原版 ReadBook.saveRead:905-910）
            val chapter = runCatching {
                AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, durChapterIndex)
            }.getOrNull()
            val durChapterTitle = chapter?.let { c ->
                runCatching {
                    c.getDisplayTitle(
                        ContentProcessorProviders.get().getTitleReplaceRules(book),
                        book.getUseReplaceRule(),
                    )
                }.getOrDefault(c.title)
            } ?: book.durChapterTitle
            AppDbProviders.get().bookDao.updateProgress(
                bookUrl = book.bookUrl,
                durChapterIndex = durChapterIndex,
                durChapterPos = durChapterPos,
                durChapterTime = systemCurrentTimeMillis(),
                durChapterTitle = durChapterTitle,
            )
        }.onFailure {
            AppLog.put("保存书籍阅读进度信息出错\n$it", it)
        }
    }

    // region WebDav 进度同步（对照 app 端 BaseReadViewModel.syncProgress/uploadProgress + ReadBookViewModel.initBook）
    /** 已触发过云进度拉取的 bookUrl：每本书打开只拉一次（原版 initBook 仅入口同步一次）。 */
    private var cloudSyncedBookUrl: String? = null

    /**
     * 进度同步专用作用域：不随 UI scope 取消。
     * 原版 uploadProgress 走进程级 MainScope（Coroutine.async），退出阅读时 VM 已 cleared 上传也不被打断；
     * shared 版等价用独立 SupervisorJob + IO 作用域（[scope] 桌面端为 rememberCoroutineScope，dispose 即取消）。
     */
    private val progressSyncScope = CoroutineScope(SupervisorJob() + IoDispatcher)

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
        if (!runCatching { AppConfigProviders.get().syncBookProgress }.getOrDefault(false)) return
        progressSyncScope.launch {
            val progress = AppWebDavShared.getBookProgress(book)
            if (progress == null || progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                    && progress.durChapterPos < book.durChapterPos)
            ) {
                uploadProgressAwait(book.bookUrl)
            } else if (progress.durChapterIndex > book.durChapterIndex ||
                progress.durChapterPos > book.durChapterPos
            ) {
                ReadBookEvents.postConfirmNewProgress(progress)
            }
        }
    }

    /**
     * 用户确认同步云端进度（原版 ReadBookActivity.sureNewProgress okButton → ReadBook.setProgress）：
     * 越界守卫 + index/pos 未变则跳过，跳转后重载当前章并落库。
     */
    fun confirmSyncProgress(progress: BookProgress) {
        ReadBookEvents.clearNewProgressConfirm()
        if (progress.durChapterIndex >= readBook.chapterSize) return
        if (readBook.durChapterIndex.value == progress.durChapterIndex &&
            readBook.durChapterPos.value == progress.durChapterPos
        ) return
        // 原版 setProgress：赋 index/pos + clearTextChapter + loadContent(resetPageOffset=true)。
        // 先置 index 再 loadChapter，避免 loadChapter 的跳章分支把 durChapterPos 清零
        readBook.clearTextChapter()
        readBook.updateDurChapterIndex(progress.durChapterIndex)
        readBook.updateDurChapterPos(progress.durChapterPos)
        loadChapter(progress.durChapterIndex)
        saveProgress()
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
        val bookUrl = readBook.book.value?.bookUrl ?: return
        progressSyncScope.launch {
            uploadProgressAwait(bookUrl)
        }
    }

    /**
     * 上传核心：先 [saveProgressAwait] 落库（原版 onPause 先 saveRead 再 uploadProgress），
     * 再读 DB 最新行构造 BookProgress 上传（[saveProgress] 只 PATCH DB 不回写内存 book 实体，
     * 直接用 readBook.book.value 会带旧 index/pos）。
     * 上传成功后持久化 syncTime（原版 book.update()；这里 update 的是刚读出的短窗口快照行，
     * 避免长持有实体整行冲写并发修改）。
     */
    private suspend fun uploadProgressAwait(bookUrl: String) {
        saveProgressAwait()
        // 进度落库后通知书架重查 (对齐原版: 退出阅读返回书架时, 书架在 Activity onResume
        // 重订阅 Room 流拿到最新进度/排序)。单页架构下书架 DB 流全程驻留, 阅读期间不摘订阅,
        // 退出只靠 Room 连续失效推送; 显式 postEvent 让书架重启当前分组流, 保证立即刷新。
        postEvent(EventBus.UP_BOOKSHELF, bookUrl)
        if (!runCatching { AppConfigProviders.get().syncBookProgress }.getOrDefault(false)) return
        runCatching {
            val fresh = AppDbProviders.get().bookDao.getBook(bookUrl) ?: return
            val syncTimeBefore = fresh.syncTime
            // 内部已守卫 syncBookProgress/authorization，成功时写 fresh.syncTime
            AppWebDavShared.uploadBookProgress(fresh)
            currentCoroutineContext().ensureActive()
            if (fresh.syncTime != syncTimeBefore) {
                AppDbProviders.get().bookDao.update(fresh)
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传阅读进度失败\n${it.message}", it)
        }
    }

    /**
     * 退出阅读界面时调用（平台侧 DisposableEffect.onDispose / VM onCleared）：
     * 落库 + 上传进度（原版 ReadBookActivity.onPause 的 saveRead + uploadProgress）。
     * 走 [progressSyncScope]，UI scope 取消不影响本次落库/上传。
     */
    fun onCleared() {
        ActiveReadBookRegistry.detachViewModel(this)
        uploadProgress()
        clearExpiredChapterLoadingJobs(clearAll = true)
        reviewCountBookUrl = null
        preDownloadTask?.cancel()
        downloadScope.coroutineContext.cancelChildren()
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
            runCatching {
                val source = AppDbProviders.get().bookSourceDao.getBookSource(book.origin) ?: return@launch
                source.enabled = false
                AppDbProviders.get().bookSourceDao.update(source)
            }
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
     * 清除当前页朗读高亮 span 并刷新内容 (对照 app 端 ALOUD_STATE STOP/PAUSE 分支:
     * `page.removePageAloudSpan()` + `readView.upContent(resetPageOffset = false)`)。
     *
     * 原版按 `durChapterPos` 定位所在页 (getPageByReadPos)，shared 等价为
     * `durPageIndexValue` 对应页；高亮是原地修改，额外自增 [pageContentVersion]
     * 驱动 Compose 重绘（等价 upContent 的 invalidate）。
     */
    fun clearAloudSpanForCurrentPage() {
        val page = readBook.curTextChapter.value?.getPage(readBook.durPageIndexValue) ?: return
        page.removePageAloudSpan()
        bumpPageContentVersion()
    }

    /**
     * 朗读进度推进 (对照 app 端 TTS_PROGRESS sticky 观察者:
     * `ReadBook.durChapterPos = chapterStart` + `page.upPageAloudSpan(aloudSpanStart)` + `upContent()`)。
     *
     * - 仅朗读播放中推进（原版守卫 `BaseReadAloudService.isPlay()`，忽略停止/暂停后的粘性重放）
     * - `updateReadPosition` 等价原版 `durChapterPos = chapterStart` + `upContent`：
     *   跨页时页面流随之翻到朗读页（原版 pageIndex 由 durChapterPos 派生）
     * - `aloudSpanStart = chapterStart - getReadLength(pageIndex)` 为页内字符偏移，
     *   交给 [io.legado.app.ui.book.read.page.entities.TextPage.upPageAloudSpan] 按段落置高亮行
     */
    fun onTtsProgress(chapterStart: Int) {
        val platform = ReadBookPlatforms.get()
        if (!platform.isReadAloudRun || platform.isReadAloudPause) return
        val textChapter = readBook.curTextChapter.value ?: return
        updateReadPosition(chapterStart)
        val pageIndex = readBook.durPageIndexValue
        val aloudSpanStart = chapterStart - textChapter.getReadLength(pageIndex)
        textChapter.getPage(pageIndex)?.upPageAloudSpan(aloudSpanStart)
        bumpPageContentVersion()
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
                // 成功: 清当前章已处理内容缓存 + 重载滑窗 (对照 app 端 onChapterListUpdated 触发 loadContent)
                processedContentCache.remove(readBook.durChapterIndex.value)
                readBook.clearTextChapter()
                // 先清消息再重载：upMsg(null) 经 onUpContent 刷新页面流，
                // 当前章已清空时直接展示"加载数据中…"占位
                readBook.upMsg(null)
                val index = readBook.durChapterIndex.value
                launchChapterLoad(index) { loadContent(index) }
                launchChapterLoad(index + 1) { loadContent(index + 1) }
                launchChapterLoad(index - 1) { loadContent(index - 1) }
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
            runCatching { ContentProcessorProviders.get().upReplaceRules() }
            val book = readBook.book.value ?: return@launch
            val index = readBook.durChapterIndex.value
            // 清已处理内容缓存, 强制重新走 ContentProcessor 链路 (含新替换规则)
            processedContentCache.remove(index)
            readBook.clearTextChapter()
            launchChapterLoad(index) { loadContent(index) }
        }
    }

    /**
     * 书源编辑后刷新 (对照 app 端 ReadBookViewModel.upBookSource(success) + BaseReadViewModel.upSource/onUpSource)。
     *
     * 重新从 DB 加载书源 (app 端 onUpSource: `ReadBook.bookSource = appDb.bookSourceDao.getBookSource(book.origin)`)
     * 并刷新当前章正文。success 在书源加载完成后触发。
     */
    fun upBookSource(success: (() -> Unit)? = null) {
        scope.launch {
            val book = readBook.book.value
            if (book != null) {
                // 重新从 DB 加载书源 (对照 app 端 onUpSource)
                val source = runCatching {
                    AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
                }.getOrNull()
                readBook.updateBookSource(source)
                // 刷新当前章 (对照 app 端 upSource 后内容刷新)
                processedContentCache.remove(readBook.durChapterIndex.value)
                readBook.clearTextChapter()
                val index = readBook.durChapterIndex.value
                launchChapterLoad(index) { loadContent(index) }
            }
            success?.invoke()
        }
    }
    // endregion

    // region 内容管理 (对照 app 端 ReadBookViewModel 同名方法)

    /**
     * 翻到指定章节 (对照 app 端 ReadBookViewModel.openChapter -> ReadBook.openChapter)。
     *
     * 清三章滑窗 + 跳章 + 进度归零 + 重载当前章及前后章。
     *
     * @param index 章节序号
     * @param durChapterPos 章内字符位置 (默认 0 = 章首)
     * @param success 加载启动回调 (与 app 端 success 时机差异: app 端在 loadContent 完成后触发,
     *   shared 端 loadChapter 是 fire-and-forget, 这里在编排启动后立即触发, 供 UI 刷新菜单状态)
     */
    fun openChapter(index: Int, durChapterPos: Int = 0, success: (() -> Unit)? = null) {
        if (index !in 0 until readBook.chapterSize) return
        // 对照 app 端 ReadBook.openChapter: clearTextChapter + 跳章 + 进度归零 + loadContent(resetPageOffset=true)
        readBook.clearTextChapter()
        readBook.updateDurChapterPos(durChapterPos)
        // loadChapter 内部会 updateDurChapterIndex + clearExpiredChapterLoadingJobs + loadContent + 预载
        loadChapter(index)
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
            runCatching {
                // 删的是当前书: 清 readBook.book 引用 (对照 app 端 Book.delete 的 ReadBook.book = null)
                if (readBook.book.value?.bookUrl == book.bookUrl) {
                    readBook.bookValue = null
                }
                AppDbProviders.get().bookDao.delete(book)
                book.addType(BookType.notShelf)
            }.onSuccess {
                success?.invoke()
            }
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
            for (i in durIndex..chapterList.lastIndex) {
                val chapter = chapterList.getOrNull(i) ?: continue
                runCatching { BookStorageProviders.get().delContent(book, chapter) }
            }
            // 清当前章已处理内容缓存 + 重载 (对照 app 端 ReadBook.loadContent(false))
            processedContentCache.remove(durIndex)
            readBook.clearTextChapter()
            launchChapterLoad(durIndex) { loadContent(durIndex) }
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
            val chapter = readBook.chapterList.value.getOrNull(durIndex)
                ?: runCatching {
                    AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, durIndex)
                }.getOrNull()
                ?: return@launch
            runCatching { BookHelpShared.saveContent(book, chapter, content) }
            // 清当前章已处理内容缓存 + 重载 (对照 app 端 ReadBook.loadContent(durChapterIndex, resetPageOffset=false))
            processedContentCache.remove(durIndex)
            readBook.clearTextChapter()
            launchChapterLoad(durIndex) { loadContent(durIndex) }
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
            val chapter = readBook.chapterList.value.getOrNull(durIndex)
                ?: runCatching {
                    AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, durIndex)
                }.getOrNull()
                ?: return@launch
            // 翻转去重标记 (对照 app 端 BookHelp.setRemoveSameTitle(book, chapter, !sameTitleRemoved))
            BookHelpShared.setRemoveSameTitleMarker(book, chapter, !textChapter.sameTitleRemoved)
            // 清当前章已处理内容缓存 + 重载 (对照 app 端 ReadBook.loadContent(durChapterIndex))
            processedContentCache.remove(durIndex)
            readBook.clearTextChapter()
            launchChapterLoad(durIndex) { loadContent(durIndex) }
        }
    }
    // endregion

    /**
     * 用 [layoutConfig] 构造 [SimpleChapterLayout] 实例。
     *
     * 每次调用新建（排版参数可能动态变化，如窗口尺寸变化后）。
     * 度量器优先取 [TextMeasurerProviders] 注册的平台真实字形实现（desktop = SkiaTextMeasurer），
     * 未注册（iOS / 鸿蒙）才回退 [SimpleTextMeasurer] 等宽近似。
     */
    private fun buildLayout(): SimpleChapterLayout {
        val cfg = _layoutConfig.value
        val measurer = TextMeasurerProviders
            .createOrNull(cfg.textSizePx, cfg.letterSpacingPx, cfg.textFontPath)
            ?: SimpleTextMeasurer(
                textSizePx = cfg.textSizePx,
                letterSpacingPx = cfg.letterSpacingPx,
                descent = cfg.textSizePx * 0.2f,
            )
        // 标题独立度量器：对应 app 端 titlePaint（字号 = textSize + titleSize），
        // 与绘制侧 ReaderDrawStyle.titleStyle 同一字号口径
        val titleMeasurer = TextMeasurerProviders
            .createOrNull(cfg.titleSizePx, cfg.letterSpacingPx, cfg.textFontPath)
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
            // 真实字体高度（descent - ascent，对应 app 端 contentPaintTextHeight = paint.textHeight），
            // 行距 = textHeight * lineSpacingExtra，与 app 端口径一致
            textHeight = measurer.descent - measurer.ascent,
            descent = measurer.descent,
            lineSpacingExtra = cfg.lineSpacingExtra,
            paragraphSpacing = cfg.paragraphSpacing,
            titleTopSpacing = cfg.titleTopSpacing,
            titleBottomSpacing = cfg.titleBottomSpacing,
            endPadding = cfg.endPadding,
            paragraphIndent = cfg.paragraphIndent,
            textFullJustify = cfg.textFullJustify,
            useZhLayout = cfg.useZhLayout,
            viewWidth = cfg.viewWidth,
            textBottomJustify = cfg.textBottomJustify,
            // 几何缩进宽度：对应 app 端 indentCharWidth = getDesiredWidth(paragraphIndent) / 长度
            indentCharWidth = cfg.paragraphIndent.takeIf { it.isNotEmpty() }?.let {
                measurer.measureWidth(it) / it.length
            } ?: 0f,
            indentChar = "　",
            titleMode = cfg.titleMode,
            titleMeasurer = titleMeasurer,
            titleTextHeight = titleMeasurer.descent - titleMeasurer.ascent,
            titleDescent = titleMeasurer.descent,
            reviewChar = "▨",
            srcReplaceChar = ChapterContentParserShared.srcReplaceChar,
        )
    }

    /**
     * 取消窗口外尚未完成的段评 IO；已完成结果保留供同一本书回翻复用。
     * 对照 app ReadBook.clearExpiredChapterLoadingJob 中 reviewCountDeferred 分支。
     */
    private fun clearExpiredReviewCount(clearAll: Boolean = false) {
        synchronized(syncLock) {
            val iterator = reviewCountDeferred.iterator()
            while (iterator.hasNext()) {
                val (index, deferred) = iterator.next()
                if (clearAll || (!deferred.isCompleted && index !in readBook.durChapterIndex.value - 1..readBook.durChapterIndex.value + 1)) {
                    deferred.cancel()
                    iterator.remove()
                }
            }
        }
    }

    /** 章节加载互斥（对照 app 端 ReadBook.addLoading / removeLoading） */
    private fun addLoading(index: Int): Boolean = synchronized(syncLock) {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        true
    }

    private fun removeLoading(index: Int) {
        synchronized(syncLock) {
            loadingChapters.remove(index)
        }
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
    /**
     * 预下载前后章节（原版 ReadBook.preDownload：Semaphore(2) 限流 + 失败 3 次跳过 +
     * 反向预载 min(5, preDownloadNum) 章；preDownloadNum < 2 时仅做 upToc）。
     */
    private fun preDownload() {
        if (readBook.book.value?.isLocal == true) return
        scope.launch {
            val preDownloadNum = runCatching {
                AppConfigProviders.get().preDownloadNum
            }.getOrDefault(10)
            if (preDownloadNum < 2) {
                upToc()
                return@launch
            }
            preDownloadTask?.cancel()
            preDownloadTask = downloadScope.launch {
                val durIndex = readBook.durChapterIndex.value
                //预下载
                launch {
                    val maxChapterIndex = min(durIndex + preDownloadNum, readBook.chapterSize)
                    for (i in durIndex.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
                //反向预载 min(5, preDownloadNum) 章
                launch {
                    val minChapterIndex = durIndex - min(5, preDownloadNum)
                    for (i in durIndex.minus(2) downTo minChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    /**
     * 预下载单章（对照 app 端 ReadBook.downloadIndex）：已缓存记账，未缓存经
     * CacheBookShared 限流下载（并发去重由其 onDownloadSet 内部保证，不占 loading 标记）。
     */
    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > readBook.chapterSize - 1) return
        val book = readBook.book.value ?: return
        val chapter = readBook.chapterList.value.getOrNull(index)
            ?: runCatching {
                AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, index)
            }.getOrNull()
            ?: return
        if (runCatching { BookStorageProviders.get().hasContent(book, chapter) }.getOrDefault(false)) {
            downloadedChapters.add(chapter.index)
        } else {
            // 失败计数同步自 CacheBookShared.errorDownloadMap（原版经 CacheBook 回调写 ReadBook.downloadFailChapters）
            CacheBookShared.errorDownloadMap[chapter.primaryStr()]?.let {
                downloadFailChapters[index] = it
            }
            if ((downloadFailChapters[index] ?: 0) >= 3) return
            delay(1000)
            val bookSource = readBook.bookSource.value ?: return
            val cacheBook = CacheBookShared.getOrCreate(bookSource, book)
            if (cacheBook.chapterList == null) {
                cacheBook.chapterList = readBook.chapterList.value
            }
            cacheBook.download(downloadScope, chapter, preDownloadSemaphore)
        }
    }

    /** 取消预下载（对照 app 端 ReadBook.cancelPreDownloadTask，正文加载完成后由平台侧调用） */
    fun cancelPreDownloadTask() {
        if (readBook.curTextChapter.value != null) {
            preDownloadTask?.cancel()
            downloadScope.coroutineContext.cancelChildren()
        }
    }

    /**
     * 阅读中自动更新目录（对照 app 端 ReadBook.upToc：canUpdate 判定 + 剩余章节 >=3 守卫 +
     * 600000ms 节流；目录增长时落库并补载下一章）。
     */
    fun upToc() {
        synchronized(syncLock) {
            val bookSource = readBook.bookSource.value ?: return
            val book = readBook.book.value ?: return
            if (!book.canUpdate) return
            if (readBook.chapterSize - readBook.durChapterIndex.value - 1 >= 3) return
            if (systemCurrentTimeMillis() - book.lastCheckTime < 600000) return
            book.lastCheckTime = systemCurrentTimeMillis()
            val oldBook = book.copy()
            scope.launch {
                runCatching {
                    WebBook.getChapterListAwait(bookSource, book).getOrThrow()
                }.onSuccess { cList ->
                    ensureActive()
                    if (cList.size > readBook.chapterSize) {
                        if (oldBook.bookUrl == book.bookUrl) {
                            AppDbProviders.get().bookDao.update(book)
                        } else {
                            AppDbProviders.get().bookDao.replace(oldBook, book)
                            BookStorageProviders.get().updateCacheFolder(oldBook, book)
                        }
                        AppDbProviders.get().bookChapterDao.delByBook(oldBook.bookUrl)
                        AppDbProviders.get().bookChapterDao.insert(*cList.toTypedArray())
                        readBook.updateChapterList(cList)
                        if (readBook.nextTextChapter.value == null) {
                            loadContent(readBook.durChapterIndex.value + 1)
                        }
                    }
                }
            }
        }
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
            // 本地 txt 解析句柄缓存清空 (对照原版 UPDATE_TOC: it.getHandler().clear()), 失败不阻断
            runCatching { FileBookProviders.get().getHandler(book).clear() }.onFailure {
                AppLog.put("更新目录失败\n${it.message}", it)
            }
            if (book.isEpub) {
                runCatching { BookStorageProviders.get().clearCache(book) }.onFailure {
                    AppLog.put("更新目录失败\n${it.message}", it)
                }
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
            launchChapterLoad(index) { loadContent(index) }
            launchChapterLoad(index + 1) { loadContent(index + 1) }
            launchChapterLoad(index - 1) { loadContent(index - 1) }
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
            runCatching { BookStorageProviders.get().clearCache(book) }
                .onFailure { AppLog.put("清理缓存失败\n${it.message}", it) }
            processedContentCache.clear()
            readBook.clearTextChapter()
            val index = readBook.durChapterIndex.value
            launchChapterLoad(index) { loadContent(index) }
            launchChapterLoad(index + 1) { loadContent(index + 1) }
            launchChapterLoad(index - 1) { loadContent(index - 1) }
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
            runCatching {
                ContentProcessorProviders.get().upReplaceRules()
                AppDbProviders.get().bookDao.update(book)
            }.onFailure { AppLog.put("切换替换规则失败\n${it.message}", it) }
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
            runCatching { AppDbProviders.get().bookDao.update(book) }
                .onFailure { AppLog.put("切换重新分段失败\n${it.message}", it) }
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
            runCatching { AppDbProviders.get().bookDao.update(book) }
                .onFailure { AppLog.put("切换标签删除失败\n${it.message}", it) }
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
            val progress = AppWebDavShared.getBookProgress(book)
            if (progress == null || progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                    && progress.durChapterPos < book.durChapterPos)
            ) {
                saveProgressAwait()
                runCatching {
                    val fresh =
                        AppDbProviders.get().bookDao.getBook(book.bookUrl) ?: return@runCatching
                    val syncTimeBefore = fresh.syncTime
                    // 内部已守卫 syncBookProgress/authorization，成功时写 fresh.syncTime
                    AppWebDavShared.uploadBookProgress(fresh) { uploadSuccessAction() }
                    currentCoroutineContext().ensureActive()
                    if (fresh.syncTime != syncTimeBefore) {
                        AppDbProviders.get().bookDao.update(fresh)
                    }
                }.onFailure {
                    currentCoroutineContext().ensureActive()
                    AppLog.put("上传阅读进度失败\n${it.message}", it)
                }
            } else if (progress.durChapterIndex > book.durChapterIndex ||
                progress.durChapterPos > book.durChapterPos
            ) {
                ReadBookEvents.postConfirmNewProgress(progress)
            } else {
                syncSuccessAction()
            }
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
            isCompleted = true
        }
        if (cfg.visibleWidth <= 0 || cfg.visibleHeight <= 0 || msg.isEmpty()) return page
        val measurer = TextMeasurerProviders
            .createOrNull(cfg.textSizePx, cfg.letterSpacingPx, cfg.textFontPath)
            ?: SimpleTextMeasurer(
                textSizePx = cfg.textSizePx,
                letterSpacingPx = cfg.letterSpacingPx,
                descent = cfg.textSizePx * 0.2f,
            )
        val textHeight = measurer.descent - measurer.ascent
        val lineSpacing = textHeight * cfg.lineSpacingExtra
        // 贪心换行：按 \n 分段，逐字累积宽度，超出可见宽度断行（消息文案短，足够）
        val wrappedLines = arrayListOf<String>()
        for (segment in msg.split('\n')) {
            if (segment.isEmpty()) {
                wrappedLines.add("")
                continue
            }
            val widths = FloatArray(segment.length)
            measurer.measureGlyphWidths(segment, widths)
            val sb = StringBuilder()
            var lineWidth = 0f
            for (i in segment.indices) {
                if (sb.isNotEmpty() && lineWidth + widths[i] > cfg.visibleWidth) {
                    wrappedLines.add(sb.toString())
                    sb.setLength(0)
                    lineWidth = 0f
                }
                sb.append(segment[i])
                lineWidth += widths[i]
            }
            wrappedLines.add(sb.toString())
        }
        // 文本块总高（行盒高 × 行数），垂直居中于可视区（对照原版 format 的
        // y = (visibleHeight - layout.height) / 2）；行水平居中（x = paddingLeft +
        // (visibleWidth - lineWidth) / 2）
        val totalHeight = wrappedLines.size * lineSpacing
        var y = cfg.paddingTop + (cfg.visibleHeight - totalHeight) / 2f
        if (y < cfg.paddingTop) y = cfg.paddingTop.toFloat()
        for (lineText in wrappedLines) {
            val textLine = TextLine(text = lineText)
            textLine.lineTop = y
            textLine.lineBottom = y + textHeight
            // baseline = lineTop - ascent（ascent 为负，同正文行 lineBase = lineBottom - descent）
            textLine.lineBase = y - measurer.ascent
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
     * @param paragraphSpacing 段间距（对应 app 端 `ChapterProvider.paragraphSpacing`）
     * @param titleTopSpacing 标题顶部留白（px）
     * @param titleBottomSpacing 标题底部留白（px）
     * @param paragraphIndent 段落缩进字符串（默认全角空格 `　　`）
     * @param textFullJustify 是否两端对齐
     * @param textBottomJustify 是否底部对齐
     * @param useZhLayout 是否启用 ZhLineBreaker 中文避头尾断行
     * @param titleMode 标题位置 0:居左 1:居中 2:隐藏（= `ReadBookConfig.titleMode`）
     * @param textFontPath 自定义正文字体文件路径（= `ReadBookConfig.textFont`，空 = 平台默认字体）；
     *   度量侧必须与绘制侧 `loadReaderFontFamily` 用同一个文件，否则选字体后正文错位
     */
    /** 已处理的章节内容缓存（跳过下载/ContentProcessor/解析，只重排版） */
    private class ProcessedChapterContent(
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
        val paddingLeft: Int = 32,
        val paddingTop: Int = 24,
        val paddingRight: Int = 32,
        val paddingBottom: Int = 24,
        val textSizePx: Float = 40f,
        val titleSizePx: Float = 40f,
        val letterSpacingPx: Float = 0f,
        val lineSpacingExtra: Float = 1.2f,
        val paragraphSpacing: Int = 2,
        val titleTopSpacing: Int = 16,
        val titleBottomSpacing: Int = 24,
        val paragraphIndent: String = "　　",
        val textFullJustify: Boolean = true,
        // 默认值与 ReadBookConfig 一致（textBottomJustify=true / useZhLayout=false / titleMode=0）
        val textBottomJustify: Boolean = true,
        /** 末页底部留白 px（对照 app 端 getTextChapter 末尾 20dp；DEFAULT 按 2x 密度折算 40px） */
        val endPadding: Int = 40,
        val useZhLayout: Boolean = false,
        val titleMode: Int = 0,
        val textFontPath: String = "",
    ) {
        /** 可视区宽度（px，扣除左右内边距） */
        val visibleWidth: Int get() = viewWidth - paddingLeft - paddingRight

        /** 可视区高度（px，扣除上下内边距） */
        val visibleHeight: Int get() = viewHeight - paddingTop - paddingBottom

        companion object {
            /** 默认配置：桌面 720x1080 + 20sp 字号近似（textSizePx=40 @ 2x density） */
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
    val oldBook = book.copy()
    val list: List<BookChapter> = try {
        if (book.isLocal) {
            withContext(IoDispatcher) { FileBook.getChapterList(book) }
        } else {
            source ?: return emptyList()
            WebBook.getChapterListAwait(source, book, runPerJs).getOrThrow()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.put("获取目录失败\n${e.message}", e)
        return emptyList()
    }
    if (!book.isNotShelf) {
        runCatching {
            val appDb = AppDbProviders.get()
            // runPreUpdateJs 有可能改掉 bookUrl，此时按新 url 迁移书与缓存目录
            if (oldBook.bookUrl == book.bookUrl) {
                appDb.bookDao.update(book)
            } else {
                appDb.bookDao.replace(oldBook, book)
                BookStorageProviders.get().updateCacheFolder(oldBook, book)
            }
            appDb.bookChapterDao.delByBook(oldBook.bookUrl)
            appDb.bookChapterDao.insert(*list.toTypedArray())
        }.onFailure { AppLog.put("目录落库失败\n${it.message}", it) }
    }
    return list
}
