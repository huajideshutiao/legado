package io.legado.app.ui.book.manga

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseReadViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.saveRead
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.ConcurrentRateLimiter
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaContent
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import io.legado.app.utils.mapIndexed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import kotlin.math.min

class ReadMangaViewModel(application: Application) :
    BaseReadViewModel(application) {

    override var curBook: Book? = null

    var durChapterIndex = 0 //章节位置
    var chapterSize = 0//总章节
    var durChapterPos = 0
    var chapterChanged = false
    var prevMangaChapter: MangaChapter? = null
    var curMangaChapter: MangaChapter? = null
    var nextMangaChapter: MangaChapter? = null
    private val loadingChapters = arrayListOf<Int>()
    private var tocUpdating = false // 目录拉取进行中, 防恢复链反复触发 upToc
    var simulatedChapterSize = 0
    var preDownloadTask: Job? = null
    val downloadedChapters = hashSetOf<Int>()
    val downloadFailChapters = hashMapOf<Int, Int>()
    val downloadScope = CoroutineScope(SupervisorJob() + IO)
    val preDownloadSemaphore = Semaphore(2)
    var rateLimiter = ConcurrentRateLimiter(null)
    val hasNextChapter get() = durChapterIndex < simulatedChapterSize - 1

    val upContentLiveData = MutableLiveData<Unit>()
    val loadFailLiveData = MutableLiveData<Pair<String, Boolean>>()
    val showLoadingLiveData = MutableLiveData<Unit>()
    val startLoadLiveData = MutableLiveData<Unit>()
    val syncProgressLiveData = MutableLiveData<BookProgress>()

    override fun onBookSourceChanged() {
        rateLimiter = ConcurrentRateLimiter(curBookSource)
    }

    override fun onChapterListUpdated(book: Book) {
        onChapterListUpdated(book, true)
    }

    fun initMangaData(
        book: Book,
        isDiffBook: Boolean = curBook?.bookUrl != book.bookUrl,
        // 调用方可在主线程预先读取 chapterListData.value 传入, 规避 LiveData.postValue 异步未生效问题
        // (未加书架时章节列表不入库, 不能依赖 DB 兜底; 参考 ReadBookViewModel.initBook 的 withContext(Main) 写法)
        prefetchedList: List<BookChapter>? = null
    ) {
        curBook = book
        if (isDiffBook) {
            ReadTimeRecorder.setBook(ReadTimeRecorder.Source.MANGA, book.name)
        }
        val chapterList = prefetchedList ?: chapterListData.value
        chapterSize = chapterList?.size ?: runBlocking { appDb.bookChapterDao.getChapterCount(book.bookUrl) }
        simulatedChapterSize = if (book.readSimulating()) book.simulatedTotalChapterNum()
        else chapterSize
        if (isDiffBook || durChapterIndex != book.durChapterIndex) {
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos * (if (book.durChapterPos < 0) -1 else 1)
            clearMangaChapter()
        }
        if (durChapterIndex !in 0 until simulatedChapterSize) {
            book.durChapterIndex = 0
            durChapterIndex = 0
            durChapterPos = 0
        }
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun clearMangaChapter() {
        prevMangaChapter = null
        curMangaChapter = null
        nextMangaChapter = null
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

    fun loadContent() {
        clearMangaChapter()
        loadContent(durChapterIndex)
        if (durChapterIndex + 1 < chapterSize) loadContent(durChapterIndex + 1)
        if (durChapterIndex - 1 >= 0) loadContent(durChapterIndex - 1)
    }

    fun loadOrUpContent() {
        if (curMangaChapter == null) loadContent(durChapterIndex)
        else upContentLiveData.postValue(Unit)
        if (nextMangaChapter == null) loadContent(durChapterIndex + 1)
        if (prevMangaChapter == null) loadContent(durChapterIndex - 1)
    }

    private fun loadContent(index: Int) {
        execute {
            val book = curBook ?: return@execute
            val chapter =
                chapterListData.value?.getOrNull(index)
                    ?: appDb.bookChapterDao.getChapter(book.bookUrl, index)
                    ?: run {
                        // 章节缺失: 目录未就绪/列表末尾, 交由 upToc 拉目录恢复或报失败, 不能静默断头
                        if (index < simulatedChapterSize || curMangaChapter == null) {
                            upToc(true)
                        }
                        return@execute
                    }
            if (addLoading(index)) {
                BookHelp.getContent(book, chapter)?.let {
                    contentLoadFinish(chapter, it)
                } ?: run {
                    download(downloadScope, chapter)
                }
            }
        }.onError {
            AppLog.put("加载正文出错\n${it.localizedMessage}")
        }
    }

    /**
     * 内容加载完成
     */
    suspend fun contentLoadFinish(
        chapter: BookChapter,
        content: String?,
        errorMsg: String = "加载内容失败",
        canceled: Boolean = false
    ) {
        removeLoading(chapter.index)
        if (canceled || chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        when (val offset = chapter.index - durChapterIndex) {
            0 -> {
                if (content == null) {
                    loadFailLiveData.postValue(errorMsg to true)
                    return
                }
                if (content.isEmpty() && !chapter.isVolume) {
                    loadFailLiveData.postValue("正文内容为空" to true)
                    return
                }
                val mangaChapter = getManageChapter(chapter, content)
                if (mangaChapter.imageCount == 0 && !chapter.isVolume) {
                    loadFailLiveData.postValue("正文没有图片" to true)
                    return
                }
                curMangaChapter = mangaChapter
                upContentLiveData.postValue(Unit)
            }

            -1, 1 -> {
                if (content == null || (!chapter.isVolume && content.isEmpty())) {
                    return
                }
                val mangaChapter = getManageChapter(chapter, content)
                if (mangaChapter.imageCount == 0 && !chapter.isVolume) {
                    return
                }

                when (offset) {
                    -1 -> prevMangaChapter = mangaChapter
                    1 -> nextMangaChapter = mangaChapter
                }

                // 当前章尚未加载完成时, 不触发 upContent, 避免 submitList 只含 prev/next 内容
                // 导致 RecyclerView 自动定位到 position=0 (prev/next 首页),
                // 误触发 onScrolled -> moveToPrevChapter/Next, 造成章节错位 (清缓存后复现)
                if (curMangaChapter != null) {
                    upContentLiveData.postValue(Unit)
                }
            }
        }
    }

    fun buildMangaContent(): MangaContent {
        val items = arrayListOf<BaseMangaPage>()
        var pos = 0
        var curFinish = false
        var nextFinish = false
        prevMangaChapter?.let {
            pos += it.pages.size
            items.addAll(it.pages)
        }
        curMangaChapter?.let {
            curFinish = true
            items.addAll(it.pages)
            durChapterPos = if (it.imageCount > 0) {
                durChapterPos.coerceIn(0, it.imageCount - 1)
            } else {
                0
            }
            pos += durChapterPos
            if (!AppConfig.hideMangaTitle && it.imageCount > 0) {
                pos++
            }
        }
        nextMangaChapter?.let {
            nextFinish = true
            items.addAll(it.pages)
        }
        return MangaContent(pos, items, curFinish, nextFinish)
    }

    /**
     * 加载下一章
     */
    fun moveToNextChapter(toFirst: Boolean = false): Boolean {
        if (durChapterIndex < simulatedChapterSize - 1) {
            if (toFirst) {
                showLoadingLiveData.postValue(Unit)
                durChapterPos = 0
            }
            durChapterIndex++
            prevMangaChapter = curMangaChapter
            curMangaChapter = nextMangaChapter
            nextMangaChapter = null
            if (curMangaChapter == null) {
                startLoadLiveData.postValue(Unit)
                loadContent(durChapterIndex)
            } else {
                upContentLiveData.postValue(Unit)
            }
            loadContent(durChapterIndex + 1)
            saveRead()
            curPageChanged()
            return true
        } else {
            return false
        }
    }

    fun moveToPrevChapter(toFirst: Boolean = false): Boolean {
        if (durChapterIndex > 0) {
            if (toFirst) {
                showLoadingLiveData.postValue(Unit)
                durChapterPos = 0
            }
            durChapterIndex--
            nextMangaChapter = curMangaChapter
            curMangaChapter = prevMangaChapter
            prevMangaChapter = null
            if (curMangaChapter == null) {
                loadContent(durChapterIndex)
            } else {
                upContentLiveData.postValue(Unit)
            }
            loadContent(durChapterIndex - 1)
            saveRead()
            return true
        }
        return false
    }

    fun curPageChanged() {
        preDownload()
    }

    fun saveRead() {
        execute {
            kotlin.runCatching {
                val book = curBook ?: return@execute
                book.durChapterIndex = durChapterIndex
                book.durChapterPos =
                    durChapterPos * (if (curMangaChapter?.imageCount == durChapterPos + 1) -1 else 1)
                appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                        book.getUseReplaceRule()
                    )
                }
                book.saveRead()
            }.onFailure {
                AppLog.put("保存漫画阅读进度信息出错\n$it", it)
            }
        }
    }

    private fun downloadNetworkContent(
        bookSource: BookSource,
        scope: CoroutineScope,
        chapter: BookChapter,
        book: Book,
        semaphore: Semaphore?,
        success: suspend (String) -> Unit = {},
        error: suspend () -> Unit = {},
        cancel: suspend () -> Unit = {},
    ) {
        val nextChapterUrl = chapterListData.value?.getOrNull(chapter.index + 1)?.url
        Coroutine.async(
            scope,
            start = CoroutineStart.LAZY,
            semaphore = semaphore
        ) {
            WebBook.getContentAwait(bookSource, book, chapter, nextChapterUrl)
        }.onSuccess { content ->
            success.invoke(content)
        }.onError {
            error.invoke()
        }.onCancel {
            cancel.invoke()
        }.start()
    }

    private fun preDownload() {
        if (curBook?.isLocal == true) return
        execute {
            if (AppConfig.preDownloadNum < 2) {
                upToc()
                return@execute
            }
            preDownloadTask?.cancel()
            preDownloadTask = downloadScope.launch {
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
        if (curMangaChapter != null && nextMangaChapter != null) {
            preDownloadTask?.cancel()
            downloadScope.coroutineContext.cancelChildren()
        }
    }

    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > chapterSize - 1) {
            upToc()
            return
        }
        val book = curBook ?: return
        val chapter =
            chapterListData.value?.getOrNull(index) ?: appDb.bookChapterDao.getChapter(
                book.bookUrl,
                index
            )
            ?: run {
                upToc(true)
                return
            }
        if (BookHelp.hasContent(book, chapter)) {
            downloadedChapters.add(chapter.index)
        } else {
            delay(1000)
            if (addLoading(index)) {
                download(downloadScope, chapter, preDownloadSemaphore)
            }
        }
    }

    /**
     * 获取正文
     */
    private suspend fun download(
        scope: CoroutineScope,
        chapter: BookChapter,
        semaphore: Semaphore? = null,
    ) {
        val book = curBook ?: return removeLoading(chapter.index)
        val bookSource = curBookSource
        if (bookSource != null) {
            downloadNetworkContent(bookSource, scope, chapter, book, semaphore, success = {
                downloadedChapters.add(chapter.index)
                downloadFailChapters.remove(chapter.index)
                contentLoadFinish(chapter, it)
            }, error = {
                downloadFailChapters[chapter.index] =
                    (downloadFailChapters[chapter.index] ?: 0) + 1
                contentLoadFinish(chapter, null)
            }, cancel = {
                contentLoadFinish(chapter, null, canceled = true)
            })
        } else {
            contentLoadFinish(chapter, null, "加载内容失败 没有书源")
        }
    }

    @Synchronized
    fun upToc(force: Boolean = false) {
        val bookSource = curBookSource ?: run {
            // 无书源必然拉不到目录, 明确报失败而非静默断头
            loadFailLiveData.postValue(appCtx.getString(R.string.error_load_toc) to true)
            return
        }
        val book = curBook ?: return
        if (!force) {
            if (!book.canUpdate) return
            if (chapterSize - durChapterIndex - 1 >= 3) return
            if (System.currentTimeMillis() - book.lastCheckTime < 600000) return
        }
        if (tocUpdating) return // 拉取进行中, 防 loadContent 恢复链反复触发
        tocUpdating = true
        book.lastCheckTime = System.currentTimeMillis()
        val oldBook = book.copy()
        execute { WebBook.getChapterListAwait(bookSource, book).getOrThrow() }
            .onSuccess { cList ->
                ensureActive()
                if (cList.size > chapterSize) {
                    if (oldBook.bookUrl == book.bookUrl) {
                        appDb.bookDao.update(book)
                    } else {
                        appDb.bookDao.replace(oldBook, book)
                        BookHelp.updateCacheFolder(oldBook, book)
                    }
                    if (!oldBook.isNotShelf) {
                        appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                        appDb.bookChapterDao.insert(*cList.toTypedArray())
                    }
                    // setValue 同步生效, 恢复链立即可读新目录 (postValue 有异步窗口)
                    chapterListData.value = cList
                    onChapterListUpdated(book, false)
                    // 触发拉取的当前章可能仍缺失, 优先补当前章再补下一章
                    if (curMangaChapter == null) loadContent(durChapterIndex)
                    nextMangaChapter ?: loadContent(durChapterIndex + 1)
                } else if (curMangaChapter == null) {
                    // 目录无新增且当前章仍缺失: 原逻辑静默断头, 改为明确失败交回用户重试
                    loadFailLiveData.postValue(appCtx.getString(R.string.error_load_toc) to true)
                }
            }
            .onError { loadFailLiveData.postValue(appCtx.getString(R.string.error_load_toc) to true) }
            .onFinally { tocUpdating = false }
    }


    /**
     * 同步阅读进度, 逻辑在 [BaseReadViewModel.syncProgress]
     */
    fun syncProgress(
        newProgressAction: ((progress: BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null,
    ) {
        val book = curBook ?: return
        syncProgress(book, newProgressAction, uploadSuccessAction, syncSuccessAction)
    }

    fun setProgress(progress: BookProgress) {
        if (progress.durChapterIndex < chapterSize &&
            (durChapterIndex != progress.durChapterIndex
                || durChapterPos != progress.durChapterPos)
        ) {
            showLoadingLiveData.postValue(Unit)
            if (progress.durChapterIndex == durChapterIndex) {
                durChapterPos = progress.durChapterPos
                upContentLiveData.postValue(Unit)
            } else {
                durChapterIndex = progress.durChapterIndex
                durChapterPos = progress.durChapterPos
                loadContent()
            }
            saveRead()
        }
    }

    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) {
        if (newBook.isSameNameAuthor(curBook)) {
            curBook = newBook

            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndex > simulatedChapterSize - 1) {
                durChapterIndex = simulatedChapterSize - 1
            }
            if (chapterSize == 0 || loadContent) {
                chapterSize = newBook.totalChapterNum
                loadContent()
            }
        }
    }

    private suspend fun getManageChapter(chapter: BookChapter, content: String): MangaChapter {
        val list = BookHelp.flowImages(chapter, content)
            .distinctUntilChanged().mapIndexed { index, src ->
                MangaPage(
                    chapterIndex = chapter.index,
                    chapterSize = chapterSize,
                    mImageUrl = src,
                    index = index,
                    mChapterName = chapter.title
                )
            }.toList()

        val imageCount = list.size

        list.forEach {
            it.imageCount = imageCount
        }

        if (AppConfig.hideMangaTitle && imageCount > 0) {
            return MangaChapter(chapter, list, imageCount)
        }

        val pages = mutableListOf<BaseMangaPage>()

        if (imageCount == 0 && chapter.isVolume) {
            pages.add(ReaderLoading(chapter.index, -1, chapter.title, true))
        } else {
            pages.add(ReaderLoading(chapter.index, -1, "阅读 ${chapter.title}"))
            pages.addAll(list)
        }

        return MangaChapter(chapter, pages, imageCount)
    }

    override fun onSourceChanged(book: Book, toc: List<BookChapter>) {
        chapterListData.postValue(toc)
        // toc 已在手中, 直接传入避免依赖 postValue 异步时序, 与 initManga 修复同类问题
        initMangaData(book, prefetchedList = toc)
        loadContent()
    }

    override fun applyProgress(progress: BookProgress) {
        setProgress(progress)
    }

    override fun getSyncProgressMsg(): String = "已同步最新漫画阅读进度"

    /**
     * 初始化
     */
    fun initData(intent: Intent, success: (() -> Unit)? = null) {
        execute {
            val book = IntentData.book ?: curBook
            when {
                book != null -> {
                    chapterChanged = intent.getBooleanExtra("chapterChanged", false)
                    val overrideIndex = intent.getIntExtra("chapterIndex", -1)
                    val overridePos = intent.getIntExtra("chapterPos", 0)
                    if (overrideIndex >= 0) {
                        intent.removeExtra("chapterIndex")
                        intent.removeExtra("chapterPos")
                    }
                    val isSameBook = curBook?.bookUrl == book.bookUrl
                    upBook(book)
                    // upBook 结束后 curBook 已是 Book, 这里再改 durChapter*, 让 initMangaData 走清缓存分支
                    if (overrideIndex >= 0) {
                        curBook?.durChapterIndex = overrideIndex
                        curBook?.durChapterPos = overridePos
                    }
                    initManga(curBook!!, isSameBook)
                }

                else -> context.getString(R.string.no_book)//没有找到书
            }
        }.onSuccess {
            success?.invoke()
        }.onError {
            AppLog.put("初始化数据失败\n${it.localizedMessage}", it)
        }
    }

    private suspend fun initManga(book: Book, isSameBook: Boolean) {
        // upBook 中 chapterListData.postValue(tmp) 是异步的, IO 线程立即读取会拿到旧值(null)
        // 未加书架时章节列表又未入库, runBlocking 查 DB 返回 0, 导致 chapterSize/simulatedChapterSize = 0
        // 这里切换到主线程等待 postValue 生效后再取值, 与 ReadBookViewModel.initBook 保持一致
        val chapterList = withContext(Dispatchers.Main) { chapterListData.value }
        initMangaData(book, isDiffBook = !isSameBook, prefetchedList = chapterList)
        //开始加载内容
        if (!isSameBook) loadContent()
        else loadOrUpContent()

        if (chapterChanged) {
            // 有章节跳转不同步阅读进度
            chapterChanged = false
        } else if (inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                syncProgress(
                    { progress -> syncProgressLiveData.postValue(progress) })
            } else syncBookProgress(book)
        }
        //自动换源
        if (!book.isLocal && curBookSource == null) {
            autoChangeSource(book.name, book.author)
            return
        }
    }

    fun openChapter(index: Int, durChapterPos: Int = 0) {
        if (index < chapterSize) {
            showLoadingLiveData.postValue(Unit)
            durChapterIndex = index
            this.durChapterPos = durChapterPos * (if (durChapterPos < 0) -1 else 1)
            saveRead()
            loadContent()
        }
    }

    fun refreshContentDur(book: Book) {
        execute {
            appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)
                ?.let { chapter ->
                    BookHelp.delContent(book, chapter)
                    openChapter(durChapterIndex, durChapterPos)
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        preDownloadTask?.cancel()
        downloadScope.coroutineContext.cancelChildren()
    }
}
