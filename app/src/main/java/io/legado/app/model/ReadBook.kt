package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PageAnim.scrollPageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.fileBook.TextFile
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.CacheBookService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.utils.postEvent
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min


@Suppress("MemberVisibilityCanBePrivate")
object ReadBook : CoroutineScope by MainScope() {
    var book: Book? = null
    var callBack: CallBack? = null
    var inBookshelf = false
    var chapterList : List<BookChapter>? = null
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var isLocalBook = true
    var chapterChanged = false
    var prevTextChapter: TextChapter? = null
    var curTextChapter: TextChapter? = null
    var nextTextChapter: TextChapter? = null
    var bookSource: BookSource? = null
    var msg: String? = null
    private val loadingChapters = arrayListOf<Int>()
    private val chapterLoadingJobs = ConcurrentHashMap<Int, Coroutine<*>>()
    private val prevChapterLoadingLock = Mutex()
    private val curChapterLoadingLock = Mutex()
    private val nextChapterLoadingLock = Mutex()

    // 段评数迟到→重排时缓存已就绪的 map，避免重排再起一次 IO 又被布局非阻塞 peek 错过造成死循环
    private val pendingReviewCountMap = ConcurrentHashMap<Int, Map<Int, Int>>()

    /* 跳转进度前进度记录 */
    var lastBookProgress: BookProgress? = null

    /* web端阅读进度记录 */
    var webBookProgress: BookProgress? = null

    var preDownloadTask: Job? = null
    val downloadedChapters = hashSetOf<Int>()
    val downloadFailChapters = hashMapOf<Int, Int>()
    var contentProcessor: ContentProcessor? = null
    val downloadScope = CoroutineScope(SupervisorJob() + IO)
    val preDownloadSemaphore = Semaphore(2)
    val executor = globalExecutor

    fun initData(book: Book) {
        releaseAndCancel()
        val isDiffBook = this.book?.bookUrl != book.bookUrl
        this.book = book
        if (isDiffBook){
            ReadTimeRecorder.setBook(ReadTimeRecorder.Source.READ_BOOK, book.name)
        }
        if (chapterList?.get(0)?.bookUrl != book.bookUrl){
            chapterList = null
        }
        chapterSize = chapterList?.size ?: appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) book.simulatedTotalChapterNum()
        else chapterSize
        contentProcessor = ContentProcessor.get(book)
        if (isDiffBook||durChapterIndex != book.durChapterIndex){
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos  * (if (book.durChapterPos<0)-1 else 1)
            isLocalBook = book.isLocal
            clearTextChapter()
        }
        if (!isDiffBook){
            if (curTextChapter?.isCompleted == false) {
                curTextChapter = null
            }
            if (nextTextChapter?.isCompleted == false) {
                nextTextChapter = null
            }
            if (prevTextChapter?.isCompleted == false) {
                prevTextChapter = null
            }
        }else{
            callBack?.upContent()
            callBack?.upPageAnim()
            lastBookProgress = null
            webBookProgress = null
            TextFile.clear()
        }

        callBack?.upMenuView()
        upWebBook(book)
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun upWebBook(book: Book) {
        if (book.isLocal) {
            bookSource = null
            if (book.getImageStyle().isNullOrBlank() && (book.isImage || book.isPdf)) {
                book.setImageStyle(Book.imgStyleFull)
            }
        } else {
            appDb.bookSourceDao.getBookSource(book.origin)?.let {
                bookSource = it
                if (book.getImageStyle().isNullOrBlank()) {
                    var imageStyle = it.getContentRule().imageStyle
                    if (imageStyle.isNullOrBlank() && (book.isImage || book.isPdf)) {
                        imageStyle = Book.imgStyleFull
                    }
                    book.setImageStyle(imageStyle)
                }
            } ?: let {
                bookSource = null
            }
        }
    }

    fun upReadBookConfig(book: Book) {
        val oldIndex = ReadBookConfig.styleSelect
        ReadBookConfig.isComic = book.isImage
        if (oldIndex != ReadBookConfig.styleSelect) {
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
        }
    }

    fun setProgress(progress: BookProgress) {
        if (progress.durChapterIndex < chapterSize &&
            (durChapterIndex != progress.durChapterIndex
                    || durChapterPos != progress.durChapterPos)
        ) {
            durChapterIndex = progress.durChapterIndex
            durChapterPos = progress.durChapterPos
            clearTextChapter()
            callBack?.upContent()
            loadContent(resetPageOffset = true)
        }
    }

    //暂时保存跳转前进度
    fun saveCurrentBookProgress() {
        if (lastBookProgress != null) return //避免进度条连续跳转不能覆盖最初的进度记录
        lastBookProgress = book?.let { BookProgress(it) }
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
        prevTextChapter = null
        curTextChapter = null
        nextTextChapter = null
    }

    fun clearSearchResult() {
        curTextChapter?.clearSearchResult()
        prevTextChapter?.clearSearchResult()
        nextTextChapter?.clearSearchResult()
    }

    fun uploadProgress(toast: Boolean = false, successAction: (() -> Unit)? = null) {
        book?.let {
            launch(IO) {
                AppWebDav.uploadBookProgress(it, toast) {
                    successAction?.invoke()
                }
                ensureActive()
                it.update()
            }
        }
    }

    /**
     * 同步阅读进度
     * 如果当前进度快于服务器进度或者没有进度进行上传，如果慢与服务器进度则执行传入动作
     */
    fun syncProgress(
        newProgressAction: ((progress: BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        val book = book ?: return
        Coroutine.async {
            AppWebDav.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败", it)
        }.onSuccess { progress ->
            if (progress == null || progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                        && progress.durChapterPos < book.durChapterPos)
            ) {
                // 服务器没有进度或者进度比服务器快，上传现有进度
                Coroutine.async {
                    AppWebDav.uploadBookProgress(BookProgress(book), uploadSuccessAction)
                    book.update()
                }
            } else if (progress.durChapterIndex > book.durChapterIndex ||
                progress.durChapterPos > book.durChapterPos
            ) {
                // 进度比服务器慢，执行传入动作
                newProgressAction?.invoke(progress)
            } else {
                syncSuccessAction?.invoke()
            }
        }
    }

    fun upMsg(msg: String?) {
        if (ReadBook.msg != msg) {
            ReadBook.msg = msg
            callBack?.upContent()
        }
    }

    fun moveToNextPage(): Boolean {
        var hasNextPage = false
        curTextChapter?.let {
            val nextPagePos = it.getNextPageLength(durChapterPos)
            if (nextPagePos >= 0) {
                hasNextPage = true
                it.getPage(durPageIndex)?.removePageAloudSpan()
                durChapterPos = nextPagePos
                callBack?.cancelSelect()
                callBack?.upContent()
            }
        }
        return hasNextPage
    }

    fun moveToPrevPage(): Boolean {
        var hasPrevPage = false
        curTextChapter?.let {
            val prevPagePos = it.getPrevPageLength(durChapterPos)
            if (prevPagePos >= 0) {
                hasPrevPage = true
                durChapterPos = prevPagePos
                callBack?.upContent()
            }
        }
        return hasPrevPage
    }

    fun moveToNextChapter(upContent: Boolean, upContentInPlace: Boolean = true): Boolean {
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContent()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
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
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContentAwait()
                loadContentAwait(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContentAwait()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
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
        if (durChapterIndex > 0) {
            durChapterPos = if (toLast) prevTextChapter?.lastReadLength ?: Int.MAX_VALUE else 0
            durChapterIndex--
            clearExpiredChapterLoadingJob()
            nextTextChapter = curTextChapter
            curTextChapter = prevTextChapter
            prevTextChapter = null
            if (curTextChapter == null) {
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                callBack?.upContent()
            }
            loadContent(durChapterIndex.minus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            curPageChanged()
            return true
        } else {
            return false
        }
    }

    fun skipToPage(index: Int, success: (() -> Unit)? = null) {
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        callBack?.upContent {
            success?.invoke()
        }
        curPageChanged()
    }

    fun setPageIndex(index: Int) {
        recycleRecorders(durPageIndex, index)
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        curPageChanged(true)
    }

    fun recycleRecorders(beforeIndex: Int, afterIndex: Int) {
        if (!AppConfig.optimizeRender) {
            return
        }
        executor.execute {
            val textChapter = curTextChapter ?: return@execute
            if (afterIndex > beforeIndex) {
                textChapter.getPage(afterIndex - 2)?.recycleRecorders()
            }
            if (afterIndex < beforeIndex) {
                textChapter.getPage(afterIndex + 3)?.recycleRecorders()
            }
        }
    }

    fun openChapter(
        index: Int,
        durChapterPos: Int = 0,
        upContent: Boolean = true,
        success: (() -> Unit)? = null
    ) {
        if (index < chapterSize) {
            clearTextChapter()
            if (upContent) callBack?.upContent()
            durChapterIndex = index
            ReadBook.durChapterPos = durChapterPos
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
        callBack?.pageChanged()
        curTextChapter?.let {
            it.notifyPageChanged()
            if (BaseReadAloudService.isRun && it.isCompleted) {
                val scrollPageAnim = pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    ReadAloud.pause(appCtx)
                } else {
                    readAloud(!BaseReadAloudService.pause)
                }
            }
        }
        preDownload()
    }

    /**
     * 朗读
     */
    fun readAloud(play: Boolean = true, startPos: Int = 0) {
        book ?: return
        val textChapter = curTextChapter ?: return
        if (textChapter.isCompleted) {
            ReadAloud.play(appCtx, play, startPos = startPos)
        }
    }

    /**
     * 当前页数
     */
    val durPageIndex: Int
        get() {
            return curTextChapter?.getPageIndexByCharIndex(durChapterPos) ?: durChapterPos
        }

    val isScroll inline get() = pageAnim() == scrollPageAnim

    val contentLoadFinish get() = curTextChapter != null || msg != null

    /**
     * chapterOnDur: 0为当前页,1为下一页,-1为上一页
     */
    fun textChapter(chapterOnDur: Int = 0): TextChapter? {
        return when (chapterOnDur) {
            0 -> curTextChapter
            1 -> nextTextChapter
            -1 -> prevTextChapter
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
        loadContent(durChapterIndex, resetPageOffset = resetPageOffset) {
            success?.invoke()
        }
        loadContent(durChapterIndex + 1, resetPageOffset = resetPageOffset)
        loadContent(durChapterIndex - 1, resetPageOffset = resetPageOffset)
    }

    fun loadOrUpContent() {
        if (curTextChapter == null) {
            loadContent(durChapterIndex)
        } else {
            callBack?.upContent()
        }
        if (nextTextChapter == null) {
            loadContent(durChapterIndex + 1)
        }
        if (prevTextChapter == null) {
            loadContent(durChapterIndex - 1)
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
            val book = book!!
            val chapter = chapterList?.get(durChapterIndex) ?: appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return@async
            if (addLoading(index)) {
                BookHelp.getContent(book, chapter)?.let {
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
            AppLog.put("加载正文出错\n${it.localizedMessage}")
        }
    }

    suspend fun loadContentAwait(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) = withContext(IO) {
        if (addLoading(index)) {
            try {
                val book = book!!
                val chapter = chapterList?.get(durChapterIndex) ?: appDb.bookChapterDao.getChapter(book.bookUrl, index)!!
                val content = BookHelp.getContent(book, chapter) ?: downloadAwait(chapter)
                contentLoadFinishAwait(book, chapter, content, upContent, resetPageOffset)
                success?.invoke()
            } catch (e: Exception) {
                AppLog.put("加载正文出错\n${e.localizedMessage}")
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
        if (index > chapterSize - 1)return
        val book = book ?: return
        val chapter = chapterList?.get(index) ?: appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return
        if (BookHelp.hasContent(book, chapter)) {
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
        val book = book ?: return removeLoading(chapter.index)
        val bookSource = bookSource
        if (bookSource != null) {
            CacheBook.getOrCreate(bookSource, book).download(scope, chapter, semaphore)
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
        val book = book!!
        val bookSource = bookSource
        if (bookSource != null) {
            return CacheBook.getOrCreate(bookSource, book).downloadAwait(chapter)
        } else {
            val msg = if (book.isLocal) "无内容" else "没有书源"
            return "加载正文失败\n$msg"
        }
    }

    @Synchronized
    private fun addLoading(index: Int): Boolean {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        return true
    }

    @Synchronized
    fun removeLoading(index: Int) {
        loadingChapters.remove(index)
    }

    /**
     * 内容加载完成
     */
    @Synchronized
    fun contentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        canceled: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        removeLoading(chapter.index)
        if (canceled || chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        chapterLoadingJobs[chapter.index]?.cancel()
        val job = Coroutine.async(this, start = CoroutineStart.LAZY) {
            // 段评数 IO 与正文排版无依赖，先启动；排版 job 内部 await，由 layout 决定哪些段挂气泡
            val countDeferred = startReviewCountFetchAsync(book, chapter)
            val textChapter = processContent(book, chapter, content, countDeferred)
            when (val offset = chapter.index - durChapterIndex) {
                0 -> curChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureActive()
                        curTextChapter = textChapter
                    }
                    callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        val index = page.index
                        if (!available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.upContent(offset, resetPageOffset)
                            }
                            available = true
                        }
                        if (upContent && isScroll) {
                            if (max(index - 3, 0) < durPageIndex) {
                                callBack?.upContent(offset, false)
                            }
                        }
                        callBack?.onLayoutPageCompleted(index, page)
                    }
                    if (upContent) callBack?.upContent(offset, !available && resetPageOffset)
                    curPageChanged()
                    callBack?.contentLoadFinish()
                    scheduleReviewRelayoutIfNeeded(countDeferred, chapter, textChapter)
                }

                -1 -> prevChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureActive()
                        prevTextChapter = textChapter
                    }
                    textChapter.layoutChannel.receiveAsFlow().collect()
                    if (upContent) callBack?.upContent(offset, resetPageOffset)
                }

                1 -> nextChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureActive()
                        nextTextChapter = textChapter
                    }
                    for (page in textChapter.layoutChannel) {
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) callBack?.upContent(offset, resetPageOffset)
                    }
                }
            }

            return@async
        }.onError {
            if (it is CancellationException) {
                return@onError
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }.onSuccess {
            success?.invoke()
        }
        chapterLoadingJobs[chapter.index] = job
        job.start()
    }

    suspend fun contentLoadFinishAwait(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean
    ) {
        removeLoading(chapter.index)
        if (chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        kotlin.runCatching {
            // 段评数 IO 与正文排版无依赖，先起步；layout 内非阻塞 peek，没到就先不挂气泡，
            // scheduleReviewRelayoutIfNeeded 负责后到触发重排。
            val countDeferred = startReviewCountFetchAsync(book, chapter)
            val textChapter = processContent(book, chapter, content, countDeferred)
            when (val offset = chapter.index - durChapterIndex) {
                0 -> {
                    curTextChapter?.cancelLayout()
                    withContext(Main) {
                        curTextChapter = textChapter
                    }
                    callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        val index = page.index
                        if (!available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.upContent(offset, resetPageOffset)
                            }
                            available = true
                        }
                        if (upContent && isScroll) {
                            if (max(index - 3, 0) < durPageIndex) {
                                callBack?.upContent(offset, false)
                            }
                        }
                        callBack?.onLayoutPageCompleted(index, page)
                    }
                    if (upContent) callBack?.upContent(offset, !available && resetPageOffset)
                    curPageChanged()
                    callBack?.contentLoadFinish()
                    scheduleReviewRelayoutIfNeeded(countDeferred, chapter, textChapter)
                }

                -1 -> {
                    prevTextChapter?.cancelLayout()
                    withContext(Main) {
                        prevTextChapter = textChapter
                    }
                    textChapter.layoutChannel.receiveAsFlow().collect()
                    if (upContent) callBack?.upContent(offset, resetPageOffset)
                }

                1 -> {
                    nextTextChapter?.cancelLayout()
                    withContext(Main) {
                        nextTextChapter = textChapter
                    }
                    for (page in textChapter.layoutChannel) {
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) callBack?.upContent(offset, resetPageOffset)
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) {
                return@onFailure
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }
    }

    private fun CoroutineScope.processContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        reviewCountDeferred: Deferred<Map<Int, Int>?>? = null,
    ): TextChapter {
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val displayTitle = chapter.getDisplayTitle(
            contentProcessor.getTitleReplaceRules(),
            book.getUseReplaceRule()
        )
        val contents = contentProcessor
            .getContent(book, chapter, content, includeTitle = false)
        ensureActive()
        return ChapterProvider.getTextChapterAsync(
            this, book, chapter, displayTitle, contents,
            simulatedChapterSize, reviewCountDeferred
        )
    }

    /**
     * 与正文加载并行启动段评数 IO。
     * 返回 null 表示不需要拉（未启用 / 无书源 / 无 reviewCountRule）。
     */
    private fun CoroutineScope.startReviewCountFetchAsync(
        book: Book, chapter: BookChapter
    ): Deferred<Map<Int, Int>?>? {
        val source = bookSource ?: return null
        if (!source.enabledReview) return null
        val rule = source.ruleReview ?: return null
        if (rule.reviewUrl.isNullOrBlank()) return null
        if (rule.reviewCountRule.isNullOrBlank()) return null
        pendingReviewCountMap.remove(chapter.index)?.let { cached ->
            return CompletableDeferred<Map<Int, Int>?>().apply { complete(cached) }
        }
        return async(IO) {
            WebBook.getReviewCountAwait(source, book, chapter).getOrNull()
        }
    }

    /**
     * 段评数迟到则触发整章重排：
     * - 排版时 count 已就绪 → reviewCountApplied=true，本函数直接返回
     * - 排版时 count 尚未就绪 → 这里 await，等到后 clear+reload 当前章
     * 排版时已就绪的常见情况是 reviewCountUrl 走本地缓存/上次已 warm。
     */
    private fun CoroutineScope.scheduleReviewRelayoutIfNeeded(
        deferred: Deferred<Map<Int, Int>?>?,
        chapter: BookChapter,
        textChapter: TextChapter,
    ) {
        if (deferred == null) return
        if (textChapter.reviewCountApplied) return
        launch {
            val map = deferred.await() ?: return@launch
            if (map.isEmpty()) return@launch
            // 等期间用户翻走/换书了就别动了，下次自然带 count 重排
            if (chapter.index != durChapterIndex) return@launch
            if (curTextChapter !== textChapter) return@launch
            withContext(Main) {
                if (chapter.index != durChapterIndex) return@withContext
                if (curTextChapter !== textChapter) return@withContext
                // 先暂存 map，让重排时 startReviewCountFetchAsync 命中缓存直接返回已完成 Deferred，
                // 否则布局非阻塞 peek 又会错过新一轮 IO 触发死循环
                pendingReviewCountMap[chapter.index] = map
                clearTextChapter()
                loadContent(resetPageOffset = false)
            }
        }
    }

    fun upToc() {
        val bookSource = bookSource ?: return
        val book = book ?: return
        if (!book.canUpdate) return
        if (chapterSize - durChapterIndex - 1 >= 3) return
        if (System.currentTimeMillis() - book.lastCheckTime < 600000) return
        book.lastCheckTime = System.currentTimeMillis()
        val oldBook = book.copy()
        Coroutine.async(this) { WebBook.getChapterListAwait(bookSource, book).getOrThrow() }
        .onSuccess { cList ->
            ensureActive()
            if (cList.size > chapterSize) {
                if (oldBook.bookUrl == book.bookUrl) {
                    appDb.bookDao.update(book)
                } else {
                    appDb.bookDao.replace(oldBook, book)
                    BookHelp.updateCacheFolder(oldBook, book)
                }
                appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                appDb.bookChapterDao.insert(*cList.toTypedArray())
                onChapterListUpdated(book, false)
                nextTextChapter ?: loadContent(durChapterIndex + 1)
            }
        }
    }

    fun pageAnim(): Int {
        val anim = ReadBookConfig.pageAnim
        return if (book?.getImageStyle()
                .equals(Book.imgStyleSingle, true) && anim == scrollPageAnim
        ) {
            PageAnim.coverPageAnim
        } else {
            anim
        }
    }

    fun setCharset(charset: String) {
        book?.let {
            it.charset = charset
            callBack?.loadChapterList(it)
        }
    }

    fun saveRead() {
        executor.execute {
            kotlin.runCatching {
                val book = book ?: return@execute
                book.durChapterIndex = durChapterIndex
                book.durChapterPos = durChapterPos * (if (curTextChapter != null && curTextChapter!!.isLastIndex(durPageIndex)) -1 else 1)
                appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                        book.getUseReplaceRule()
                    )
                }
                book.saveRead()
            }.onFailure {
                AppLog.put("保存书籍阅读进度信息出错\n$it", it)
            }
        }
    }

    /**
     * 预下载
     */
    private fun preDownload() {
        if (book?.isLocal == true) return
        executor.execute {
            if (AppConfig.preDownloadNum < 2) {
                upToc()
                return@execute
            }
            preDownloadTask?.cancel()
            preDownloadTask = launch(IO) {
                //预下载
                launch {
                    val maxChapterIndex =
                        min(durChapterIndex + AppConfig.preDownloadNum, chapterSize)
                    for (i in durChapterIndex.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
                launch {
                    val minChapterIndex = durChapterIndex - min(5, AppConfig.preDownloadNum)
                    for (i in durChapterIndex.minus(2) downTo minChapterIndex) {
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
        if (newBook.isSameNameAuthor(book)) {
            book = newBook
            chapterSize = newBook.totalChapterNum
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndex > simulatedChapterSize - 1) {
                durChapterIndex = simulatedChapterSize - 1
            }
            callBack?.upMenuView()
            if (callBack == null) {
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
            if (clearAll || index !in durChapterIndex - 1..durChapterIndex + 1) {
                job.cancel()
                iterator.remove()
            }
        }
    }

    /**
     * 注册回调
     */
    fun register(cb: CallBack) {
        callBack?.notifyBookChanged()
        callBack = cb
    }

    /**
     * 取消注册回调
     */
    fun unregister(cb: CallBack) {
        if (callBack === cb) {
            callBack = null
        }
        releaseAndCancel()
    }

    private fun releaseAndCancel() {
        msg = null
        preDownloadTask?.cancel()
        downloadScope.coroutineContext.cancelChildren()
        coroutineContext.cancelChildren()
        ImageProvider.clear()
        pendingReviewCountMap.clear()
        clearExpiredChapterLoadingJob(true)
        if (!CacheBookService.isRun) {
            CacheBook.close()
        }
    }

    interface CallBack : LayoutProgressListener {
        fun upMenuView()

        fun loadChapterList(book: Book)

        fun upContent(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        )

        suspend fun upContentAwait(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        )

        fun pageChanged()

        fun contentLoadFinish()

        fun upPageAnim(upRecorder: Boolean = false)

        fun notifyBookChanged()

        fun sureNewProgress(progress: BookProgress)

        fun cancelSelect()
    }

}
