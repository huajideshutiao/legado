package io.legado.app.model

import io.legado.app.App
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.globalExecutor
import io.legado.app.model.fileBook.TextFile
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.CacheBookService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.sync.Semaphore


/**
 * app 端 ReadBook 薄壳。
 *
 * 阅读编排逻辑（章节推进 / 三章滑窗 / loadContent / 预下载 / upToc / 进度落库上传）已下沉
 * [ReadBookShared]，本 object 只做字段/方法签名转发。
 *
 * 注意：本 object 持有的 [shared] 实例与 Compose 阅读页 (ReaderScreenModel) 的实例**不是同一个**，
 * 阅读中的真实状态一律经 [ActiveReadBookRegistry] 取。平台出口 (朗读/缓存服务运行态、图片与
 * 本地 txt 缓存清理) 由 [registerAndroidReadBookPlatform] 注册给 [ReadBookPlatforms]，与本实例无关。
 */
@Suppress("MemberVisibilityCanBePrivate")
object ReadBook : CoroutineScope by MainScope() {

    /** 跨平台阅读编排核心 */
    val shared: ReadBookShared = AndroidReadBook()

    // region 状态字段转发
    var book: Book?
        get() = shared.bookValue
        set(value) {
            shared.bookValue = value
        }

    var inBookshelf: Boolean
        get() = shared.inBookshelfValue
        set(value) {
            shared.inBookshelfValue = value
        }

    var chapterList: List<BookChapter>?
        get() = shared.chapterListValue
        set(value) {
            shared.chapterListValue = value
        }

    var chapterSize: Int
        get() = shared.chapterSize
        set(value) {
            shared.chapterSize = value
        }

    var simulatedChapterSize: Int
        get() = shared.simulatedChapterSize
        set(value) {
            shared.simulatedChapterSize = value
        }

    var durChapterIndex: Int
        get() = shared.durChapterIndexValue
        set(value) {
            shared.durChapterIndexValue = value
        }

    var durChapterPos: Int
        get() = shared.durChapterPosValue
        set(value) {
            shared.durChapterPosValue = value
        }

    var isLocalBook: Boolean
        get() = shared.isLocalBook
        set(value) {
            shared.isLocalBook = value
        }

    var chapterChanged: Boolean
        get() = shared.chapterChanged
        set(value) {
            shared.chapterChanged = value
        }

    var bookSource: BookSource?
        get() = shared.bookSourceValue
        set(value) {
            shared.bookSourceValue = value
        }

    var msg: String?
        get() = shared.msg
        set(value) {
            shared.msg = value
        }

    /* 跳转进度前进度记录 */
    var lastBookProgress: BookProgress?
        get() = shared.lastBookProgress
        set(value) {
            shared.lastBookProgress = value
        }

    /* web端阅读进度记录 */
    var webBookProgress: BookProgress?
        get() = shared.webBookProgressValue
        set(value) {
            shared.webBookProgressValue = value
        }

    var preDownloadTask: Job?
        get() = shared.preDownloadTask
        set(value) {
            shared.preDownloadTask = value
        }

    val downloadedChapters: HashSet<Int> get() = shared.downloadedChapters
    val downloadFailChapters: HashMap<Int, Int> get() = shared.downloadFailChapters
    val downloadScope: CoroutineScope get() = shared.downloadScope
    val preDownloadSemaphore: Semaphore get() = shared.preDownloadSemaphore
    // endregion

    // region 方法转发
    fun initData(book: Book) = shared.initData(book)

    fun upWebBook(book: Book) = shared.upWebBook(book)

    fun upReadBookConfig(book: Book) = shared.upReadBookConfig(book)

    fun setProgress(progress: BookProgress) = shared.setProgress(progress)

    fun saveCurrentBookProgress() = shared.saveCurrentBookProgress()

    fun restoreLastBookProgress() = shared.restoreLastBookProgress()

    fun clearTextChapter() = shared.clearTextChapter()

    fun clearSearchResult() = shared.clearSearchResult()

    fun uploadProgress(toast: Boolean = false, successAction: (() -> Unit)? = null) =
        shared.uploadProgress(toast, successAction)

    fun upMsg(msg: String?) = shared.upMsg(msg)

    fun moveToNextPage(): Boolean = shared.moveToNextPage()

    fun moveToPrevPage(): Boolean = shared.moveToPrevPage()

    fun moveToNextChapter(upContent: Boolean, upContentInPlace: Boolean = true): Boolean =
        shared.moveToNextChapter(upContent, upContentInPlace)

    suspend fun moveToNextChapterAwait(
        upContent: Boolean,
        upContentInPlace: Boolean = true
    ): Boolean = shared.moveToNextChapterAwait(upContent, upContentInPlace)

    fun moveToPrevChapter(
        upContent: Boolean,
        toLast: Boolean = true,
        upContentInPlace: Boolean = true
    ): Boolean = shared.moveToPrevChapter(upContent, toLast, upContentInPlace)

    fun skipToPage(index: Int, success: (() -> Unit)? = null) = shared.skipToPage(index, success)

    fun setPageIndex(index: Int) = shared.setPageIndex(index)

    fun openChapter(
        index: Int,
        durChapterPos: Int = 0,
        upContent: Boolean = true,
        success: (() -> Unit)? = null
    ) = shared.openChapter(index, durChapterPos, upContent, success)

    fun readAloud(play: Boolean = true, startPos: Int = 0) = shared.readAloud(play, startPos)

    /** 当前页数 */
    val durPageIndex: Int get() = shared.durPageIndexValue

    val isScroll: Boolean get() = shared.isScroll

    val contentLoadFinish: Boolean get() = shared.contentLoadFinish

    fun loadContent(resetPageOffset: Boolean, success: (() -> Unit)? = null) =
        shared.loadContent(resetPageOffset, success)

    fun loadOrUpContent() = shared.loadOrUpContent()

    fun loadContent(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) = shared.loadContent(index, upContent, resetPageOffset, success)

    suspend fun loadContentAwait(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) = shared.loadContentAwait(index, upContent, resetPageOffset, success)

    fun removeLoading(index: Int) = shared.removeLoading(index)

    fun contentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        canceled: Boolean = false,
        success: (() -> Unit)? = null
    ) = shared.contentLoadFinish(
        book, chapter, content, upContent, resetPageOffset, canceled, success
    )

    suspend fun contentLoadFinishAwait(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean
    ) = shared.contentLoadFinishAwait(book, chapter, content, upContent, resetPageOffset)

    fun upToc() = shared.upToc()

    fun pageAnim(): Int = shared.pageAnim()

    fun setCharset(charset: String) = shared.setCharset(charset)

    fun saveRead() = shared.saveRead()

    fun cancelPreDownloadTask() = shared.cancelPreDownloadTask()

    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) =
        shared.onChapterListUpdated(newBook, loadContent)
    // endregion
}

/**
 * 注册 [ReadBookShared] 的 Android 平台出口 (朗读/缓存服务运行态 + 图片/本地 txt 缓存清理)。
 *
 * 宿主启动早期调用一次 (MainActivity.initializePlatform), 与其余 registerAndroidXxx 同批次。
 */
fun registerAndroidReadBookPlatform() {
    ReadBookPlatforms.register(AndroidReadBookPlatform)
}

/**
 * Android 平台出口实现：朗读服务 / 缓存服务 / 图片与本地 txt 缓存。
 */
private object AndroidReadBookPlatform : ReadBookPlatform {

    override val isReadAloudRun: Boolean get() = BaseReadAloudService.isRun

    override val isReadAloudPause: Boolean get() = BaseReadAloudService.pause

    override fun playReadAloud(play: Boolean, startPos: Int) {
        ReadAloud.play(App.instance, play, startPos = startPos)
    }

    override fun pauseReadAloud() {
        ReadAloud.pause(App.instance)
    }

    override val isCacheBookServiceRun: Boolean get() = CacheBookService.isRun

    override fun clearImageCache() {
        ImageProvider.clear()
    }

    override fun clearTextFileCache() {
        TextFile.clear()
    }
}

/**
 * Android 端线程池出口：排版走 shared SimpleChapterLayout，本类只提供后台执行器。
 */
private class AndroidReadBook : ReadBookShared() {

    override fun runOnBackground(block: () -> Unit) {
        globalExecutor.execute { block() }
    }
}
