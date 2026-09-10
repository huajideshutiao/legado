package io.legado.app.model

import io.legado.app.constant.PageAnim
import io.legado.app.constant.PageAnim.scrollPageAnim
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.coroutine.runBlockingInScope
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 跨平台阅读状态核心（app 端 `io.legado.app.model.ReadBook` 单例下沉产物）。
 *
 * **只管状态，不管装载**：书籍 / 书源 / 目录 / 当前章号与章内位置 / 三章滑窗容器 /
 * 页内翻页 / 进度落库，平台差异经 provider 与 [ReadBookPlatform] 出口。
 *
 * 章节装载编排（三章窗口装载、切章、预下载、目录自动更新）全部在
 * [io.legado.app.ui.book.read.ReadBookViewModelShared]，公共部分见
 * `io.legado.app.model.chapter`（四模式共用）。本类曾并存一套同名的旧装载链
 * （loadContent / contentLoadFinish / preDownload / upToc / moveToNextChapter…），
 * 与 VM 那套形成两份引擎且零外部调用点，已整体删除。
 *
 * 三章滑窗以 [prevTextChapter] / [curTextChapter] / [nextTextChapter] 只读暴露，
 * 可写视图见 [prevChapter] / [curChapter] / [nextChapter]；其余状态字段一律
 * 「StateFlow 只读 + `xxxValue` 可写视图」双出口。排版产物由 VM 经
 * [updateTextChapter] 回填。
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

    /** app 端 `ReadBook.prevTextChapter` 可写视图 */
    var prevChapter: TextChapterShared?
        get() = _prevTextChapter.value
        set(value) {
            _prevTextChapter.value = value
        }

    /** app 端 `ReadBook.curTextChapter` 可写视图 */
    var curChapter: TextChapterShared?
        get() = _curTextChapter.value
        set(value) {
            _curTextChapter.value = value
            _durPageIndex.value = durPageIndexValue
        }

    /** app 端 `ReadBook.nextTextChapter` 可写视图 */
    var nextChapter: TextChapterShared?
        get() = _nextTextChapter.value
        set(value) {
            _nextTextChapter.value = value
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

    /** 后台落库用作用域 (进度落库等; 章节装载与预下载在 ReadBookViewModelShared)。 */
    private val backgroundScope = CoroutineScope(SupervisorJob() + IoDispatcher)

    // region 平台出口: app 端子类覆盖, 其余端用默认实现
    /**
     * 后台执行（app 端 `globalExecutor.execute`）。默认走 IO 调度器。
     */
    protected open fun runOnBackground(block: () -> Unit) {
        backgroundScope.launch { block() }
    }
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
        if (isDiffBook) {
            callback?.upContent()
            ReadBookEvents.postConfig(ReadConfigChange.PAGE_ANIM)
            lastBookProgress = null
            webBookProgressValue = null
            platform.clearTextFileCache()
        }

        ReadBookEvents.postMenuRefresh()
        upWebBook(book)
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

    //暂时保存跳转前进度
    fun saveCurrentBookProgress() {
        if (lastBookProgress != null) return //避免进度条连续跳转不能覆盖最初的进度记录
        lastBookProgress = bookValue?.let { BookProgress(it) }
    }

    fun clearTextChapter() {
        prevChapter = null
        curChapter = null
        nextChapter = null
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
                durChapterPosValue = nextPagePos
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

    fun skipToPage(index: Int, success: (() -> Unit)? = null) {
        durChapterPosValue = curChapter?.getReadLength(index) ?: index
        callback?.upContent {
            success?.invoke()
        }
        syncReadAloudOnPageChanged()
    }

    /**
     * 页位跳变后同步朗读位置 (原版 curPageChanged 的朗读分支, 非滚动翻页那一支)。
     *
     * 原版该函数还带 `pageChanged` 参数: 滚动模式下由 `setPageIndex` 传 true 表示
     * 「用户滚动导致的页码变化」并改为暂停朗读。KMP 版滚动模式的页码变化不经本类
     * (VM 直接维护 durChapterPos), 唯一调用点 [skipToPage] 等价原版 pageChanged=false,
     * 故参数已删。原版此处还会触发预下载, 现由
     * [io.legado.app.ui.book.read.ReadBookViewModelShared] 在翻页/跳页/切章后统一触发。
     */
    private fun syncReadAloudOnPageChanged() {
        curChapter ?: return
        if (!platform.isReadAloudRun) return
        readAloud(!platform.isReadAloudPause)
    }

    /**
     * 朗读
     */
    fun readAloud(play: Boolean = true, startPos: Int = 0) {
        bookValue ?: return
        curChapter ?: return
        platform.playReadAloud(play, startPos)
    }

    /** 当前页数（对应 app 端 `ReadBook.durPageIndex` 计算属性） */
    val durPageIndexValue: Int
        get() = curChapter?.getPageIndexByCharIndex(durChapterPosValue) ?: durChapterPosValue

    val isScroll inline get() = pageAnim() == scrollPageAnim

    val contentLoadFinish get() = curChapter != null || msg != null

    fun pageAnim(): Int {
        val anim = ReadBookConfigProviders.get().pageAnim
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
            val book = bookValue ?: return@runOnBackground
            book.durChapterIndex = durChapterIndexValue
            book.durChapterPos = durChapterPosValue *
                (if (curChapter?.isLastIndex(durPageIndexValue) == true) -1 else 1)
            // 每翻一页都会走这里: 章名先取内存目录, 内存没有才兜底查库
            // (原来无条件 runBlockingInScope 查库 = 热路径上的阻塞 IO)
            (chapterListValue?.getOrNull(durChapterIndexValue)
                ?: runBlockingInScope(EmptyCoroutineContext) {
                    AppDbProviders.get().bookChapterDao.getChapter(
                        book.bookUrl,
                        durChapterIndexValue
                    )
                })?.let {
                book.durChapterTitle = it.getDisplayTitle(
                    ContentProcessorProviders.get().getTitleReplaceRules(book),
                    book.getUseReplaceRule()
                )
            }
            saveReadProgress(book)
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
     * 后台目录更新 (书架刷新 / 导出等) 通知当前阅读实例: 换新书实体 + 收敛越界章号。
     *
     * 不再触发装载: 原先无宿主时清滑窗、有宿主时调本类 loadContent —— 而本类的
     * contentLoadFinish 只做守卫不排版 (排版在 ReadBookViewModelShared), 那条路径实际只是
     * 经 CacheBookShared 预热磁盘缓存, 对显示无影响; 真正的三章窗口由 VM 独立维护。
     */
    fun onChapterListUpdated(newBook: Book) {
        if (newBook.isSameNameAuthor(bookValue)) {
            bookValue = newBook
            chapterSize = newBook.totalChapterNum
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndexValue > simulatedChapterSize - 1) {
                durChapterIndexValue = simulatedChapterSize - 1
            }
            ReadBookEvents.postMenuRefresh()
            if (callback == null) clearTextChapter()
        }
    }

    private fun releaseAndCancel() {
        msg = null
        backgroundScope.coroutineContext.cancelChildren()
        coroutineContext.cancelChildren()
        platform.clearImageCache()
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

        /**
         * 视图刷新 (对照原版 ReadBook.CallBack.upContent → ReadBookActivity.upContent)。
         *
         * 原版带 relativePosition / resetPageOffset 两参: 前者告诉 Activity 刷的是哪一章
         * (滑窗偏移), 后者控制滚动位置是否归零。KMP 版页面流由 VM 自行按滑窗推导、
         * 滚动偏移由 VM 的 applyCurChapterPages 处理, 两参在所有调用点都取默认值, 已删。
         */
        fun upContent(success: (() -> Unit)? = null) {}

        /** 装载新书 / 切书后触发 ([initData] 完成) */
        fun onBookChanged(book: Book) {}

        /** durChapterIndex 变化 (切章) 后触发 */
        fun onChapterChanged(index: Int) {}

        /** durChapterPos / durPageIndex 变化 (翻页) 后触发 */
        fun onPageChanged() {}

        /** 章节列表刷新后触发 ([updateChapterList]) */
        fun onChapterListChanged(chapterList: List<BookChapter>) {}

        /** 当前章节正文加载完成 / 内容刷新后触发 ([updateTextChapter] 的当前章分支) */
        fun onBookContentChanged() {}
    }
}
