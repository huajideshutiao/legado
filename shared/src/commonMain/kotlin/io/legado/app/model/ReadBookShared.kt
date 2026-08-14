package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PageAnim.scrollPageAnim
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.coroutine.runBlockingInScope
import io.legado.app.help.toast.Toasters
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.page.entities.TextChapterContract
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import io.legado.app.utils.concurrent.newConcurrentMap
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.min

/**
 * 跨平台阅读编排核心（app 端 `io.legado.app.model.ReadBook` 单例下沉产物）。
 *
 * 章节索引推进 / 三章滑窗 / loadContent 编排 / 预下载 / 目录自动更新 / 进度落库与上传
 * 全部照搬 app 版实现，平台差异只经三类出口：
 * - 已有 provider：[AppDbProviders] / [BookStorageProviders] / [ContentProcessorProviders] /
 *   [AppConfigProviders] / [ReadBookConfigProviders] / [CacheBookShared] / [AppWebDavShared] / [Toasters]
 * - [ReadBookPlatform]：朗读服务、缓存服务运行态、图片/本地 txt 缓存释放
 * - open 成员：[createTextChapter]（排版）/ [collectLayout]（逐页推送）/ [recycleRecorders] /
 *   [runOnBackground]，app 端子类用 ChapterProvider + TextChapterLayout + globalExecutor 覆盖
 *
 * 三章滑窗元素类型抽象为 [TextChapterContract]：app 端为 `TextChapter`（异步排版），
 * 其余端为 [TextChapterShared]（SimpleChapterLayout 同步排版）。为兼容既有 Compose 消费方，
 * [prevTextChapter] / [curTextChapter] / [nextTextChapter] 仍以 `StateFlow<TextChapterShared?>`
 * 暴露（非 TextChapterShared 实现时为 null），app 风格的可写视图见 [prevChapter] / [curChapter] /
 * [nextChapter]。同理其余状态字段一律「StateFlow 只读 + `xxxValue` 可写视图」双出口。
 */
@Suppress("MemberVisibilityCanBePrivate")
open class ReadBookShared : CoroutineScope {

    /** 对应 app 端 `ReadBook : CoroutineScope by MainScope()` */
    private val mainScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    final override val coroutineContext: CoroutineContext get() = mainScope.coroutineContext

    private val platform get() = ReadBookPlatforms.get()

    // region 状态字段: MutableStateFlow 内部可写, 外部只读 StateFlow (适配 Compose 重组)
    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    /** app 端 `ReadBook.book` 可写视图 */
    var bookValue: Book?
        get() = _book.value
        set(value) {
            _book.value = value
        }

    private val _bookSource = MutableStateFlow<BookSource?>(null)
    val bookSource: StateFlow<BookSource?> = _bookSource.asStateFlow()

    /** app 端 `ReadBook.bookSource` 可写视图 */
    var bookSourceValue: BookSource?
        get() = _bookSource.value
        set(value) {
            _bookSource.value = value
        }

    private val _chapterList = MutableStateFlow<List<BookChapter>>(emptyList())
    val chapterList: StateFlow<List<BookChapter>> = _chapterList.asStateFlow()

    private var chapterListInternal: List<BookChapter>? = null

    /** app 端 `ReadBook.chapterList` 可写视图（null = 未加载/已失效，语义与 app 一致） */
    var chapterListValue: List<BookChapter>?
        get() = chapterListInternal
        set(value) {
            chapterListInternal = value
            _chapterList.value = value ?: emptyList()
        }

    private val _durChapterIndex = MutableStateFlow(0)
    val durChapterIndex: StateFlow<Int> = _durChapterIndex.asStateFlow()

    /** app 端 `ReadBook.durChapterIndex` 可写视图 */
    var durChapterIndexValue: Int
        get() = _durChapterIndex.value
        set(value) {
            _durChapterIndex.value = value
        }

    private val _durChapterPos = MutableStateFlow(0)
    val durChapterPos: StateFlow<Int> = _durChapterPos.asStateFlow()

    /** app 端 `ReadBook.durChapterPos` 可写视图 */
    var durChapterPosValue: Int
        get() = _durChapterPos.value
        set(value) {
            _durChapterPos.value = value
            _durPageIndex.value = durPageIndexValue
        }

    private val _durPageIndex = MutableStateFlow(0)
    val durPageIndex: StateFlow<Int> = _durPageIndex.asStateFlow()

    private val _prevTextChapter = MutableStateFlow<TextChapterShared?>(null)
    val prevTextChapter: StateFlow<TextChapterShared?> = _prevTextChapter.asStateFlow()

    private val _curTextChapter = MutableStateFlow<TextChapterShared?>(null)
    val curTextChapter: StateFlow<TextChapterShared?> = _curTextChapter.asStateFlow()

    private val _nextTextChapter = MutableStateFlow<TextChapterShared?>(null)
    val nextTextChapter: StateFlow<TextChapterShared?> = _nextTextChapter.asStateFlow()

    private var prevChapterInternal: TextChapterContract? = null
    private var curChapterInternal: TextChapterContract? = null
    private var nextChapterInternal: TextChapterContract? = null

    /** app 端 `ReadBook.prevTextChapter` 可写视图 */
    var prevChapter: TextChapterContract?
        get() = prevChapterInternal
        set(value) {
            prevChapterInternal = value
            _prevTextChapter.value = value as? TextChapterShared
        }

    /** app 端 `ReadBook.curTextChapter` 可写视图 */
    var curChapter: TextChapterContract?
        get() = curChapterInternal
        set(value) {
            curChapterInternal = value
            _curTextChapter.value = value as? TextChapterShared
            _durPageIndex.value = durPageIndexValue
        }

    /** app 端 `ReadBook.nextTextChapter` 可写视图 */
    var nextChapter: TextChapterContract?
        get() = nextChapterInternal
        set(value) {
            nextChapterInternal = value
            _nextTextChapter.value = value as? TextChapterShared
        }

    private val _inBookshelf = MutableStateFlow(false)
    val inBookshelf: StateFlow<Boolean> = _inBookshelf.asStateFlow()

    /** app 端 `ReadBook.inBookshelf` 可写视图 */
    var inBookshelfValue: Boolean
        get() = _inBookshelf.value
        set(value) {
            _inBookshelf.value = value
        }

    private val _webBookProgress = MutableStateFlow<BookProgress?>(null)
    val webBookProgress: StateFlow<BookProgress?> = _webBookProgress.asStateFlow()

    /** app 端 `ReadBook.webBookProgress` 可写视图（web 端阅读进度记录） */
    var webBookProgressValue: BookProgress?
        get() = _webBookProgress.value
        set(value) {
            _webBookProgress.value = value
        }
    // endregion

    /** 章节总数 (对应 app 端 ReadBook.chapterSize) */
    var chapterSize: Int = 0

    /** 模拟章节总数 (对应 app 端 ReadBook.simulatedChapterSize) */
    var simulatedChapterSize: Int = 0

    var isLocalBook = true

    var chapterChanged = false

    var msg: String? = null

    /* 跳转进度前进度记录 */
    var lastBookProgress: BookProgress? = null

    /**
     * 回调接口。各平台注入实现 (Android = ReadBookActivity / 桌面 = Compose 适配器)。
     */
    var callback: ReadBookCallback? = null

    private val loadingChapters = arrayListOf<Int>()
    private val chapterLoadingJobs = newConcurrentMap<Int, Coroutine<*>>()
    private val prevChapterLoadingLock = Mutex()
    private val curChapterLoadingLock = Mutex()
    private val nextChapterLoadingLock = Mutex()

    // 段评数按 chapter.index 复用 Deferred，已完成的留作不切书时的缓存
    private val reviewCountDeferred = newConcurrentMap<Int, Deferred<Map<Int, Int>?>>()

    // 替代 app 端 @Synchronized（kotlin.jvm.Synchronized 无 common 变体）
    private val lock = SynchronizedObject()

    var preDownloadTask: Job? = null
    val downloadedChapters = hashSetOf<Int>()
    val downloadFailChapters = hashMapOf<Int, Int>()
    val downloadScope = CoroutineScope(SupervisorJob() + IoDispatcher)
    val preDownloadSemaphore = Semaphore(2)

    // region 平台出口: app 端子类覆盖, 其余端用默认实现
    /**
     * 后台执行（app 端 `globalExecutor.execute`）。默认走 IO 调度器。
     */
    protected open fun runOnBackground(block: () -> Unit) {
        downloadScope.launch { block() }
    }

    /**
     * 正文 → 章节排版产物（app 端 `processContent`: ContentProcessor + ChapterProvider）。
     *
     * 默认返回 null：commonMain 侧排版（SimpleChapterLayout）需要视口/字号参数，
     * 由消费方 (ReadBookViewModelShared) 注入后自行排版，故此处不做臆造实现。
     */
    protected open suspend fun createTextChapter(
        scope: CoroutineScope,
        book: Book,
        chapter: BookChapter,
        content: String,
        reviewCountDeferred: Deferred<Map<Int, Int>?>?,
    ): TextChapterContract? = null

    /**
     * 消费排版产物并刷新视图（app 端 contentLoadFinish 里的 layoutChannel 逐页推送）。
     * 同步排版的平台无逐页通道，整章刷新一次即可。
     */
    protected open suspend fun collectLayout(
        textChapter: TextChapterContract,
        offset: Int,
        upContent: Boolean,
        resetPageOffset: Boolean,
    ) {
        if (upContent) callback?.upContent(offset, resetPageOffset)
    }

    /**
     * 回收翻页缓存（app 端 CanvasRecorder，依赖 AppConfig.optimizeRender）。
     */
    open fun recycleRecorders(beforeIndex: Int, afterIndex: Int) {}
    // endregion

    fun initData(book: Book) {
        releaseAndCancel()
        val initState = calculateReadBookInitState(
            previousBookUrl = this.bookValue?.bookUrl,
            firstChapterBookUrl = chapterListValue?.firstOrNull()?.bookUrl,
            currentChapterIndex = durChapterIndexValue,
            incomingBook = book,
        )
        val isDiffBook = initState.isDifferentBook
        this.bookValue = book
        if (isDiffBook) {
            ReadTimeRecorder.setBook(ReadTimeRecorder.Source.READ_BOOK, book.name)
        }
        if (initState.shouldDropChapterList) {
            chapterListValue = null
        }
        chapterSize = chapterListValue?.size ?: runBlockingInScope(EmptyCoroutineContext) {
            AppDbProviders.get().bookChapterDao.getChapterCount(book.bookUrl)
        }
        simulatedChapterSize = if (book.readSimulating()) book.simulatedTotalChapterNum()
        else chapterSize
        if (initState.shouldResetProgress) {
            durChapterIndexValue = initState.chapterIndex
            durChapterPosValue = initState.chapterPosition
            isLocalBook = book.isLocal
            clearTextChapter()
        }
        if (!isDiffBook) {
            if (curChapter?.isCompleted == false) {
                curChapter = null
            }
            if (nextChapter?.isCompleted == false) {
                nextChapter = null
            }
            if (prevChapter?.isCompleted == false) {
                prevChapter = null
            }
        } else {
            callback?.upContent()
            ReadBookEvents.postConfig(ReadConfigChange.PAGE_ANIM)
            lastBookProgress = null
            webBookProgressValue = null
            platform.clearTextFileCache()
        }

        ReadBookEvents.postMenuRefresh()
        upWebBook(book)
        synchronized(lock) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
        callback?.onBookChanged(book)
    }

    /** 装载新书 / 切书（[initData] 的跨平台入口名，语义完全一致） */
    fun loadBook(book: Book) = initData(book)

    fun upWebBook(book: Book) {
        if (book.isLocal) {
            bookSourceValue = null
            if (book.config.imageStyle.isNullOrBlank() && (book.isImage || book.isPdf)) {
                book.config.imageStyle = Book.imgStyleFull
            }
        } else {
            runBlockingInScope(EmptyCoroutineContext) {
                AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
            }?.let {
                bookSourceValue = it
                if (book.config.imageStyle.isNullOrBlank()) {
                    var imageStyle = it.contentRule.imageStyle
                    if (imageStyle.isNullOrBlank() && (book.isImage || book.isPdf)) {
                        imageStyle = Book.imgStyleFull
                    }
                    book.config.imageStyle = imageStyle
                }
            } ?: let {
                bookSourceValue = null
            }
        }
    }

    fun upReadBookConfig(book: Book) {
        val readBookConfig = ReadBookConfigProviders.getOrNull() ?: return
        val oldIndex = readBookConfig.styleSelect
        readBookConfig.isComic = book.isImage
        if (oldIndex != readBookConfig.styleSelect) {
            ReadBookEvents.postConfig(
                ReadConfigChange.BG, ReadConfigChange.STYLE, ReadConfigChange.LOAD_CONTENT
            )
            ReadBookEvents.postActionBarChange()
        }
    }

    fun setProgress(progress: BookProgress) {
        if (progress.durChapterIndex < chapterSize &&
            (durChapterIndexValue != progress.durChapterIndex
                || durChapterPosValue != progress.durChapterPos)
        ) {
            durChapterIndexValue = progress.durChapterIndex
            durChapterPosValue = progress.durChapterPos
            clearTextChapter()
            callback?.upContent()
            loadContent(resetPageOffset = true)
        }
    }

    //暂时保存跳转前进度
    fun saveCurrentBookProgress() {
        if (lastBookProgress != null) return //避免进度条连续跳转不能覆盖最初的进度记录
        lastBookProgress = bookValue?.let { BookProgress(it) }
    }

    //恢复跳转前进度
    fun restoreLastBookProgress() {
        lastBookProgress?.let {
            setProgress(it)
            lastBookProgress = null
        }
    }

    fun clearTextChapter() {
        clearExpiredChapterLoadingJob(true)
        prevChapter = null
        curChapter = null
        nextChapter = null
    }

    fun clearSearchResult() {
        curChapter?.clearSearchResult()
        prevChapter?.clearSearchResult()
        nextChapter?.clearSearchResult()
    }

    fun uploadProgress(toast: Boolean = false, successAction: (() -> Unit)? = null) {
        bookValue?.let {
            launch(IoDispatcher) {
                AppWebDavShared.uploadBookProgress(it, toast) {
                    successAction?.invoke()
                }
                currentCoroutineContext().ensureActive()
                AppDbProviders.get().bookDao.update(it)
            }
        }
    }

    fun upMsg(msg: String?) {
        if (this.msg != msg) {
            this.msg = msg
            callback?.upContent()
        }
    }

    fun moveToNextPage(): Boolean {
        var hasNextPage = false
        curChapter?.let {
            val nextPagePos = it.getNextPageLength(durChapterPosValue)
            if (nextPagePos >= 0) {
                hasNextPage = true
                it.getPage(durPageIndexValue)?.removePageAloudSpan()
                durChapterPosValue = nextPagePos
                callback?.cancelSelect()
                callback?.upContent()
            }
        }
        return hasNextPage
    }

    fun moveToPrevPage(): Boolean {
        var hasPrevPage = false
        curChapter?.let {
            val prevPagePos = it.getPrevPageLength(durChapterPosValue)
            if (prevPagePos >= 0) {
                hasPrevPage = true
                durChapterPosValue = prevPagePos
                callback?.upContent()
            }
        }
        return hasPrevPage
    }

    fun moveToNextChapter(upContent: Boolean, upContentInPlace: Boolean = true): Boolean {
        if (durChapterIndexValue < simulatedChapterSize - 1) {
            durChapterPosValue = 0
            durChapterIndexValue++
            clearExpiredChapterLoadingJob()
            prevChapter = curChapter
            curChapter = nextChapter
            nextChapter = null
            if (curChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callback?.upContent()
                loadContent(durChapterIndexValue, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callback?.upContent()
            }
            loadContent(durChapterIndexValue.plus(1), upContent, false)
            saveRead()
            ReadBookEvents.postMenuRefresh()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged()
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    suspend fun moveToNextChapterAwait(
        upContent: Boolean,
        upContentInPlace: Boolean = true
    ): Boolean {
        if (durChapterIndexValue < simulatedChapterSize - 1) {
            durChapterPosValue = 0
            durChapterIndexValue++
            clearExpiredChapterLoadingJob()
            prevChapter = curChapter
            curChapter = nextChapter
            nextChapter = null
            if (curChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callback?.upContentAwait()
                loadContentAwait(durChapterIndexValue, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callback?.upContentAwait()
            }
            loadContent(durChapterIndexValue.plus(1), upContent, false)
            saveRead()
            ReadBookEvents.postMenuRefresh()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged()
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    fun moveToPrevChapter(
        upContent: Boolean,
        toLast: Boolean = true,
        upContentInPlace: Boolean = true
    ): Boolean {
        if (durChapterIndexValue > 0) {
            durChapterPosValue = if (toLast) prevChapter?.lastReadLength ?: Int.MAX_VALUE else 0
            durChapterIndexValue--
            clearExpiredChapterLoadingJob()
            nextChapter = curChapter
            curChapter = prevChapter
            prevChapter = null
            if (curChapter == null) {
                if (upContentInPlace) callback?.upContent()
                loadContent(durChapterIndexValue, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                callback?.upContent()
            }
            loadContent(durChapterIndexValue.minus(1), upContent, false)
            saveRead()
            ReadBookEvents.postMenuRefresh()
            curPageChanged()
            return true
        } else {
            return false
        }
    }

    fun skipToPage(index: Int, success: (() -> Unit)? = null) {
        durChapterPosValue = curChapter?.getReadLength(index) ?: index
        callback?.upContent {
            success?.invoke()
        }
        curPageChanged()
    }

    fun setPageIndex(index: Int) {
        recycleRecorders(durPageIndexValue, index)
        durChapterPosValue = curChapter?.getReadLength(index) ?: index
        curPageChanged(true)
    }

    fun openChapter(
        index: Int,
        durChapterPos: Int = 0,
        upContent: Boolean = true,
        success: (() -> Unit)? = null
    ) {
        if (index < chapterSize) {
            clearTextChapter()
            if (upContent) callback?.upContent()
            durChapterIndexValue = index
            durChapterPosValue = durChapterPos
            saveRead()
            loadContent(resetPageOffset = true) {
                success?.invoke()
            }
        }
    }

    /**
     * 当前页面变化
     */
    private fun curPageChanged(pageChanged: Boolean = false) {
        callback?.pageChanged()
        curChapter?.let {
            it.notifyPageChanged()
            if (platform.isReadAloudRun && it.isCompleted) {
                val scrollPageAnim = pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    platform.pauseReadAloud()
                } else {
                    readAloud(!platform.isReadAloudPause)
                }
            }
        }
        preDownload()
    }

    /**
     * 朗读
     */
    fun readAloud(play: Boolean = true, startPos: Int = 0) {
        bookValue ?: return
        val textChapter = curChapter ?: return
        if (textChapter.isCompleted) {
            platform.playReadAloud(play, startPos)
        }
    }

    /** 当前页数（对应 app 端 `ReadBook.durPageIndex` 计算属性） */
    val durPageIndexValue: Int
        get() = curChapter?.getPageIndexByCharIndex(durChapterPosValue) ?: durChapterPosValue

    val isScroll inline get() = pageAnim() == scrollPageAnim

    val contentLoadFinish get() = curChapter != null || msg != null

    /**
     * chapterOnDur: 0为当前页,1为下一页,-1为上一页
     */
    fun textChapter(chapterOnDur: Int = 0): TextChapterContract? {
        return when (chapterOnDur) {
            0 -> curChapter
            1 -> nextChapter
            -1 -> prevChapter
            else -> null
        }
    }

    /**
     * 加载当前章节和前后一章内容
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 当前章节加载完成回调
     */
    fun loadContent(
        resetPageOffset: Boolean,
        success: (() -> Unit)? = null
    ) {
        loadContent(durChapterIndexValue, resetPageOffset = resetPageOffset) {
            success?.invoke()
        }
        loadContent(durChapterIndexValue + 1, resetPageOffset = resetPageOffset)
        loadContent(durChapterIndexValue - 1, resetPageOffset = resetPageOffset)
    }

    fun loadOrUpContent() {
        if (curChapter == null) {
            loadContent(durChapterIndexValue)
        } else {
            callback?.upContent()
        }
        if (nextChapter == null) {
            loadContent(durChapterIndexValue + 1)
        }
        if (prevChapter == null) {
            loadContent(durChapterIndexValue - 1)
        }
    }

    /**
     * 加载章节内容
     * @param index 章节序号
     * @param upContent 是否更新视图
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 加载完成回调
     */
    fun loadContent(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        Coroutine.async {
            val book = bookValue!!
            val chapter = chapterListValue?.getOrNull(index)
                ?: AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, index)
                ?: return@async
            if (addLoading(index)) {
                startReviewCountFetchAsync(book, chapter)
                BookHelpProviders.get().getContent(book, chapter)?.let {
                    contentLoadFinish(
                        book,
                        chapter,
                        it,
                        upContent,
                        resetPageOffset,
                        success = success
                    )
                } ?: download(
                    downloadScope,
                    chapter,
                    resetPageOffset
                )
            }
        }.onError {
            AppLog.put("加载正文出错\n${it.message}")
        }
    }

    suspend fun loadContentAwait(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) = withContext(IoDispatcher) {
        if (addLoading(index)) {
            try {
                val book = bookValue!!
                val chapter = chapterListValue?.getOrNull(index)
                    ?: AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, index)!!
                startReviewCountFetchAsync(book, chapter)
                val content = BookHelpProviders.get().getContent(book, chapter)
                    ?: downloadAwait(chapter)
                contentLoadFinishAwait(book, chapter, content, upContent, resetPageOffset)
                success?.invoke()
            } catch (e: Exception) {
                AppLog.put("加载正文出错\n${e.message}")
            } finally {
                removeLoading(index)
            }
        }
    }

    /**
     * 下载正文
     */
    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > chapterSize - 1) return
        val book = bookValue ?: return
        val chapter = chapterListValue?.get(index)
            ?: AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, index) ?: return
        if (BookStorageProviders.get().hasContent(book, chapter)) {
            downloadedChapters.add(chapter.index)
        } else {
            delay(1000)
            if (addLoading(index)) {
                download(downloadScope, chapter, false, preDownloadSemaphore)
            }
        }
    }

    /**
     * 下载正文
     */
    private fun download(
        scope: CoroutineScope,
        chapter: BookChapter,
        resetPageOffset: Boolean,
        semaphore: Semaphore? = null,
        success: (() -> Unit)? = null
    ) {
        val book = bookValue ?: return removeLoading(chapter.index)
        val bookSource = bookSourceValue
        if (bookSource != null) {
            val cacheBook = CacheBookShared.getOrCreate(bookSource, book)
            if (cacheBook.chapterList == null) {
                cacheBook.chapterList = chapterListValue
            }
            cacheBook.download(scope, chapter, semaphore)
        } else {
            val msg = if (book.isLocal) "无内容" else "没有书源"
            contentLoadFinish(
                book,
                chapter,
                "加载正文失败\n$msg",
                resetPageOffset = resetPageOffset,
                success = success
            )
        }
    }

    private suspend fun downloadAwait(chapter: BookChapter): String {
        val book = bookValue!!
        val bookSource = bookSourceValue
        if (bookSource != null) {
            val cacheBook = CacheBookShared.getOrCreate(bookSource, book)
            if (cacheBook.chapterList == null) {
                cacheBook.chapterList = chapterListValue
            }
            return cacheBook.downloadAwait(chapter)
        } else {
            val msg = if (book.isLocal) "无内容" else "没有书源"
            return "加载正文失败\n$msg"
        }
    }

    private fun addLoading(index: Int): Boolean = synchronized(lock) {
        if (loadingChapters.contains(index)) return@synchronized false
        loadingChapters.add(index)
        true
    }

    fun removeLoading(index: Int) {
        synchronized(lock) { removeLoadingLocked(index) }
    }

    // 已持锁时调用 (atomicfu 的锁在 Native 端不可重入, 不能在锁内再调 removeLoading)
    private fun removeLoadingLocked(index: Int) {
        loadingChapters.remove(index)
    }

    /**
     * 内容加载完成
     */
    fun contentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        canceled: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        synchronized(lock) {
            removeLoadingLocked(chapter.index)
            if (canceled || chapter.index !in durChapterIndexValue - 1..durChapterIndexValue + 1) {
                return
            }
            chapterLoadingJobs[chapter.index]?.cancel()
            val job = Coroutine.async(this, start = CoroutineStart.LAZY) {
                val countDeferred = startReviewCountFetchAsync(book, chapter)
                val textChapter = createTextChapter(this, book, chapter, content, countDeferred)
                    ?: return@async
                when (val offset = chapter.index - durChapterIndexValue) {
                    0 -> curChapterLoadingLock.withLock {
                        withContext(mainDispatcher) {
                            ensureActive()
                            curChapter = textChapter
                        }
                        ReadBookEvents.postMenuRefresh()
                        collectLayout(textChapter, offset, upContent, resetPageOffset)
                        curPageChanged()
                        callback?.contentLoadFinish()
                        scheduleReviewRelayoutIfNeeded(countDeferred, chapter, textChapter)
                    }

                    -1 -> prevChapterLoadingLock.withLock {
                        withContext(mainDispatcher) {
                            ensureActive()
                            prevChapter = textChapter
                        }
                        collectLayout(textChapter, offset, upContent, resetPageOffset)
                    }

                    1 -> nextChapterLoadingLock.withLock {
                        withContext(mainDispatcher) {
                            ensureActive()
                            nextChapter = textChapter
                        }
                        collectLayout(textChapter, offset, upContent, resetPageOffset)
                    }
                }

                return@async
            }.onError {
                if (it is CancellationException) {
                    return@onError
                }
                AppLog.put("ChapterProvider ERROR", it)
                runCatching { Toasters.get().toast("ChapterProvider ERROR:\n${it.stackTraceStr}") }
            }.onSuccess {
                success?.invoke()
            }
            chapterLoadingJobs[chapter.index] = job
            job.start()
        }
    }

    suspend fun contentLoadFinishAwait(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean
    ) {
        removeLoading(chapter.index)
        if (chapter.index !in durChapterIndexValue - 1..durChapterIndexValue + 1) {
            return
        }
        runCatching {
            // 排版作用域取本实例 (原版 processContent 是 ReadBook 这个 CoroutineScope 的扩展)
            val countDeferred = startReviewCountFetchAsync(book, chapter)
            val textChapter = createTextChapter(this, book, chapter, content, countDeferred)
                ?: return@runCatching
            when (val offset = chapter.index - durChapterIndexValue) {
                0 -> {
                    curChapter?.cancelLayout()
                    withContext(mainDispatcher) {
                        curChapter = textChapter
                    }
                    ReadBookEvents.postMenuRefresh()
                    collectLayout(textChapter, offset, upContent, resetPageOffset)
                    curPageChanged()
                    callback?.contentLoadFinish()
                    scheduleReviewRelayoutIfNeeded(countDeferred, chapter, textChapter)
                }

                -1 -> {
                    prevChapter?.cancelLayout()
                    withContext(mainDispatcher) {
                        prevChapter = textChapter
                    }
                    collectLayout(textChapter, offset, upContent, resetPageOffset)
                }

                1 -> {
                    nextChapter?.cancelLayout()
                    withContext(mainDispatcher) {
                        nextChapter = textChapter
                    }
                    collectLayout(textChapter, offset, upContent, resetPageOffset)
                }
            }
        }.onFailure {
            if (it is CancellationException) {
                return@onFailure
            }
            AppLog.put("ChapterProvider ERROR", it)
            runCatching { Toasters.get().toast("ChapterProvider ERROR:\n${it.stackTraceStr}") }
        }
    }

    /**
     * 与正文加载并行启动段评数 IO，按 chapter.index 复用 Deferred。
     * 返回 null 表示不需要拉。
     */
    private fun startReviewCountFetchAsync(
        book: Book, chapter: BookChapter
    ): Deferred<Map<Int, Int>?>? {
        val source = bookSourceValue ?: return null
        if (!source.enabledReview) return null
        if (source.ruleReview.isNullOrEmpty()) return null
        val rule = source.reviewRule
        if (rule.reviewUrl.isNullOrBlank()) return null
        if (rule.reviewCountRule.isNullOrBlank()) return null
        reviewCountDeferred[chapter.index]?.let { return it }
        val deferred = async(IoDispatcher) {
            WebBook.getReviewCountAwait(source, book, chapter).getOrNull()
        }
        reviewCountDeferred[chapter.index] = deferred
        return deferred
    }

    /**
     * 段评数迟于排版到达时触发整章重排
     */
    private fun CoroutineScope.scheduleReviewRelayoutIfNeeded(
        deferred: Deferred<Map<Int, Int>?>?,
        chapter: BookChapter,
        textChapter: TextChapterContract,
    ) {
        if (deferred == null) return
        if (textChapter.reviewCountApplied) return
        launch {
            val map = deferred.await() ?: return@launch
            if (map.isEmpty()) return@launch
            if (chapter.index != durChapterIndexValue) return@launch
            if (curChapter !== textChapter) return@launch
            withContext(mainDispatcher) {
                if (chapter.index != durChapterIndexValue) return@withContext
                if (curChapter !== textChapter) return@withContext
                clearTextChapter()
                reviewCountDeferred[chapter.index] = CompletableDeferred(map)
                loadContent(resetPageOffset = false)
            }
        }
    }

    fun upToc() {
        synchronized(lock) {
            val bookSource = bookSourceValue ?: return
            val book = bookValue ?: return
            if (!book.canUpdate) return
            if (chapterSize - durChapterIndexValue - 1 >= 3) return
            if (systemCurrentTimeMillis() - book.lastCheckTime < 600000) return
            book.lastCheckTime = systemCurrentTimeMillis()
            val oldBook = book.copy()
            Coroutine.async(this) { WebBook.getChapterListAwait(bookSource, book).getOrThrow() }
                .onSuccess { cList ->
                    ensureActive()
                    if (cList.size > chapterSize) {
                        val appDb = AppDbProviders.get()
                        if (oldBook.bookUrl == book.bookUrl) {
                            appDb.bookDao.update(book)
                        } else {
                            appDb.bookDao.replace(oldBook, book)
                            BookStorageProviders.get().updateCacheFolder(oldBook, book)
                        }
                        appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                        appDb.bookChapterDao.insert(*cList.toTypedArray())
                        onChapterListUpdated(book, false)
                        nextChapter ?: loadContent(durChapterIndexValue + 1)
                    }
                }
        }
    }

    fun pageAnim(): Int {
        val anim = ReadBookConfigProviders.getOrNull()?.pageAnim ?: PageAnim.coverPageAnim
        return if (bookValue?.config?.imageStyle
                .equals(Book.imgStyleSingle, true) && anim == scrollPageAnim
        ) {
            PageAnim.coverPageAnim
        } else {
            anim
        }
    }

    fun setCharset(charset: String) {
        bookValue?.let {
            it.charset = charset
            ReadBookEvents.postLoadChapterList(it)
        }
    }

    fun saveRead() {
        runOnBackground {
            runCatching {
                val book = bookValue ?: return@runCatching
                book.durChapterIndex = durChapterIndexValue
                book.durChapterPos = durChapterPosValue *
                    (if (curChapter?.isLastIndex(durPageIndexValue) == true) -1 else 1)
                runBlockingInScope(EmptyCoroutineContext) {
                    AppDbProviders.get().bookChapterDao.getChapter(
                        book.bookUrl,
                        durChapterIndexValue
                    )
                }?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessorProviders.get().getTitleReplaceRules(book),
                        book.getUseReplaceRule()
                    )
                }
                saveReadProgress(book)
            }.onFailure {
                AppLog.put("保存书籍阅读进度信息出错\n$it", it)
            }
        }
    }

    /**
     * 落库阅读进度（对应 app 端 `Book.saveRead()`）。
     * 仅 PATCH 进度字段，避免整行 update 冲掉后台写入的最新元数据。
     */
    private fun saveReadProgress(book: Book) {
        book.lastCheckCount = 0
        book.durChapterTime = systemCurrentTimeMillis()
        runBlockingInScope(EmptyCoroutineContext) {
            AppDbProviders.get().bookDao.updateProgress(
                book.bookUrl,
                book.durChapterIndex,
                book.durChapterPos,
                book.durChapterTime,
                book.durChapterTitle
            )
        }
        ReadTimeRecorder.flushAll()
    }

    /**
     * 预下载
     */
    private fun preDownload() {
        if (bookValue?.isLocal == true) return
        runOnBackground {
            val preDownloadNum = AppConfigProviders.get().preDownloadNum
            if (preDownloadNum < 2) {
                upToc()
                return@runOnBackground
            }
            preDownloadTask?.cancel()
            preDownloadTask = launch(IoDispatcher) {
                //预下载
                launch {
                    val maxChapterIndex =
                        min(durChapterIndexValue + preDownloadNum, chapterSize)
                    for (i in durChapterIndexValue.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
                launch {
                    val minChapterIndex = durChapterIndexValue - min(5, preDownloadNum)
                    for (i in durChapterIndexValue.minus(2) downTo minChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    fun cancelPreDownloadTask() {
        if (contentLoadFinish) {
            preDownloadTask?.cancel()
            downloadScope.coroutineContext.cancelChildren()
        }
    }

    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) {
        if (newBook.isSameNameAuthor(bookValue)) {
            bookValue = newBook
            chapterSize = newBook.totalChapterNum
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndexValue > simulatedChapterSize - 1) {
                durChapterIndexValue = simulatedChapterSize - 1
            }
            ReadBookEvents.postMenuRefresh()
            if (callback == null) {
                clearTextChapter()
            } else if (loadContent) {
                loadContent(true)
            }
        }
    }

    private fun clearExpiredChapterLoadingJob(clearAll: Boolean = false) {
        val iterator = chapterLoadingJobs.iterator()
        while (iterator.hasNext()) {
            val (index, job) = iterator.next()
            if (clearAll || index !in durChapterIndexValue - 1..durChapterIndexValue + 1) {
                job.cancel()
                iterator.remove()
            }
        }
        // 已完成的留作不切书时的缓存；未完成且出窗口的取消，避免快速翻章累积并行 IO
        val reviewIter = reviewCountDeferred.iterator()
        while (reviewIter.hasNext()) {
            val (index, deferred) = reviewIter.next()
            if (!deferred.isCompleted &&
                (clearAll || index !in durChapterIndexValue - 1..durChapterIndexValue + 1)
            ) {
                deferred.cancel()
                reviewIter.remove()
            }
        }
    }

    /**
     * 注册回调
     */
    fun register(cb: ReadBookCallback) {
        callback?.notifyBookChanged()
        callback = cb
    }

    /**
     * 取消注册回调
     */
    fun unregister(cb: ReadBookCallback) {
        if (callback === cb) {
            callback = null
        }
        releaseAndCancel()
    }

    private fun releaseAndCancel() {
        msg = null
        preDownloadTask?.cancel()
        downloadScope.coroutineContext.cancelChildren()
        coroutineContext.cancelChildren()
        platform.clearImageCache()
        reviewCountDeferred.values.forEach { it.cancel() }
        reviewCountDeferred.clear()
        clearExpiredChapterLoadingJob(true)
        if (!platform.isCacheBookServiceRun) {
            CacheBookShared.close()
        }
    }

    // region 跨平台消费方 (ReadBookViewModelShared / Compose) 使用的状态更新入口
    /** 加载完章节列表后调用, 同步 chapterSize 并通知 callback */
    fun updateChapterList(list: List<BookChapter>) {
        chapterListValue = list
        chapterSize = list.size
        // 模拟阅读进度时按日解锁章节数 (原版 ReadBook.initData:114-115)
        simulatedChapterSize = bookValue?.takeIf { it.readSimulating() }
            ?.simulatedTotalChapterNum() ?: list.size
        callback?.onChapterListChanged(list)
    }

    /** 加载完 TextChapter 后调用 */
    fun updateCurTextChapter(textChapter: TextChapterShared?) {
        updateTextChapter(0, textChapter)
    }

    /** 排版完成按滑窗位归位 (对照原版 contentLoadFinish 的 offset -1/0/+1 三分支) */
    fun updateTextChapter(offset: Int, textChapter: TextChapterShared?) {
        when (offset) {
            -1 -> prevChapter = textChapter
            0 -> {
                curChapter = textChapter
                callback?.onBookContentChanged()
            }

            1 -> nextChapter = textChapter
        }
    }

    /** 滑窗前移: prev=cur, cur=next, next=null (对照原版 moveToNextChapter 的窗口平移段) */
    fun slideTextChaptersNext() {
        prevChapter = curChapter
        curChapter = nextChapter
        nextChapter = null
        callback?.onBookContentChanged()
    }

    /** 滑窗后移: next=cur, cur=prev, prev=null (对照原版 moveToPrevChapter 的窗口平移段) */
    fun slideTextChaptersPrev() {
        nextChapter = curChapter
        curChapter = prevChapter
        prevChapter = null
        callback?.onBookContentChanged()
    }

    /** 解析到书源后调用 (对照原版 ReadBook.upWebBook 的 bookSource 赋值, 本地书传 null) */
    fun updateBookSource(source: BookSource?) {
        bookSourceValue = source
    }

    /** 切页时调用 (durChapterPos 变化) */
    fun updateDurChapterPos(pos: Int) {
        durChapterPosValue = pos
        callback?.onPageChanged()
    }

    /** 切章时调用 (durChapterIndex 变化) */
    fun updateDurChapterIndex(index: Int) {
        durChapterIndexValue = index
        callback?.onChapterChanged(index)
    }

    /** 书架状态变化时调用 */
    fun updateInBookshelf(value: Boolean) {
        inBookshelfValue = value
    }

    /** web 进度更新 (与 app 端 ReadBook.webBookProgress 对应) */
    fun updateWebBookProgress(progress: BookProgress?) {
        webBookProgressValue = progress
    }

    /**
     * 加载指定章节内容 (当前/前后一章)。
     * 消费方 (ReadBookViewModelShared) 自行完成 IO/排版后回填滑窗。
     */
    fun loadChapter(index: Int) {
        if (index < 0 || index >= chapterSize) return
        callback?.onChapterChanged(index)
    }

    /** 下一页 (对照 app 端 ReadBook.moveToNextPage)。false=已到章末需切章。 */
    fun nextPage(): Boolean {
        if (!moveToNextPage()) return false
        callback?.onPageChanged()
        return true
    }

    /** 上一页 (对照 app 端 ReadBook.moveToPrevPage)。false=已到章首需切章。 */
    fun prevPage(): Boolean {
        if (!moveToPrevPage()) return false
        callback?.onPageChanged()
        return true
    }
    // endregion

    /**
     * 跨平台 ReadBook 回调接口 (app 端 `ReadBook.CallBack` 下沉)。
     *
     * 翻页渲染等同步性能敏感回调保持 app 原签名; 异步 UI 刷新类事件走 [ReadBookEvents]。
     * onBookChanged / onChapterChanged 等语义级事件供非 Android 端 Compose 适配器消费。
     */
    interface ReadBookCallback {

        fun upContent(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        ) {
        }

        suspend fun upContentAwait(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        ) {
        }

        fun pageChanged() {}

        fun contentLoadFinish() {}

        fun notifyBookChanged() {}

        fun cancelSelect() {}

        /** 装载新书 / 切书后触发 ([initData] 完成) */
        fun onBookChanged(book: Book) {}

        /** durChapterIndex 变化 (切章) 后触发 */
        fun onChapterChanged(index: Int) {}

        /** durChapterPos / durPageIndex 变化 (翻页) 后触发 */
        fun onPageChanged() {}

        /** 章节列表刷新后触发 ([updateChapterList]) */
        fun onChapterListChanged(chapterList: List<BookChapter>) {}

        /** 当前章节正文加载完成 / 内容刷新后触发 ([updateCurTextChapter]) */
        fun onBookContentChanged() {}
    }
}
