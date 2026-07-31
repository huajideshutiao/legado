package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.config.AppConfig
import io.legado.app.help.globalExecutor
import io.legado.app.model.fileBook.TextFile
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.CacheBookService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextChapterContract
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Semaphore
import splitties.init.appCtx
import kotlin.math.max


/**
 * app 端 ReadBook 薄壳。
 *
 * 阅读编排逻辑（章节推进 / 三章滑窗 / loadContent / 预下载 / upToc / 进度落库上传）已下沉
 * [ReadBookShared]，本 object 只做两件事：
 * 1. 保持原有字段/方法签名转发，100+ 处调用点行为不变（[TextChapter] 强类型由 `as?` 还原）
 * 2. 用 [AndroidReadBook] 覆盖四个平台出口：ChapterProvider 排版、layoutChannel 逐页推送、
 *    CanvasRecorder 回收、globalExecutor 线程池
 */
@Suppress("MemberVisibilityCanBePrivate")
object ReadBook : CoroutineScope by MainScope() {

    init {
        ReadBookPlatforms.register(AndroidReadBookPlatform)
    }

    /** 跨平台阅读编排核心（Android 出口已覆盖） */
    val shared: ReadBookShared = AndroidReadBook()

    // region 状态字段转发
    var book: Book?
        get() = shared.bookValue
        set(value) {
            shared.bookValue = value
        }

    var callBack: CallBack?
        get() = shared.callback as? CallBack
        set(value) {
            shared.callback = value
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

    var prevTextChapter: TextChapter?
        get() = shared.prevChapter as? TextChapter
        set(value) {
            shared.prevChapter = value
        }

    var curTextChapter: TextChapter?
        get() = shared.curChapter as? TextChapter
        set(value) {
            shared.curChapter = value
        }

    var nextTextChapter: TextChapter?
        get() = shared.nextChapter as? TextChapter
        set(value) {
            shared.nextChapter = value
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
    val executor = globalExecutor
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

    fun recycleRecorders(beforeIndex: Int, afterIndex: Int) =
        shared.recycleRecorders(beforeIndex, afterIndex)

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

    /**
     * chapterOnDur: 0为当前页,1为下一页,-1为上一页
     */
    fun textChapter(chapterOnDur: Int = 0): TextChapter? =
        shared.textChapter(chapterOnDur) as? TextChapter

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

    /**
     * 注册回调
     */
    fun register(cb: CallBack) = shared.register(cb)

    /**
     * 取消注册回调
     */
    fun unregister(cb: CallBack) = shared.unregister(cb)
    // endregion

    // 异步 UI 刷新类回调已移入 ReadBookEvents(菜单刷新/目录重载/翻页动画/进度确认),
    // 仅保留翻页渲染等同步性能敏感回调; 方法体已下沉 ReadBookShared.ReadBookCallback
    interface CallBack : LayoutProgressListener, ReadBookShared.ReadBookCallback
}

/**
 * Android 平台出口实现：朗读服务 / 缓存服务 / 图片与本地 txt 缓存。
 */
private object AndroidReadBookPlatform : ReadBookPlatform {

    override val isReadAloudRun: Boolean get() = BaseReadAloudService.isRun

    override val isReadAloudPause: Boolean get() = BaseReadAloudService.pause

    override fun playReadAloud(play: Boolean, startPos: Int) {
        ReadAloud.play(appCtx, play, startPos = startPos)
    }

    override fun pauseReadAloud() {
        ReadAloud.pause(appCtx)
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
 * Android 端排版/渲染出口：ChapterProvider 异步排版 + layoutChannel 逐页推送 + CanvasRecorder 回收。
 */
private class AndroidReadBook : ReadBookShared() {

    override fun runOnBackground(block: () -> Unit) {
        globalExecutor.execute { block() }
    }

    /** 对应原 ReadBook.processContent */
    override suspend fun createTextChapter(
        scope: CoroutineScope,
        book: Book,
        chapter: BookChapter,
        content: String,
        reviewCountDeferred: Deferred<Map<Int, Int>?>?,
    ): TextChapterContract {
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val displayTitle = chapter.getDisplayTitle(
            contentProcessor.getTitleReplaceRules(),
            book.getUseReplaceRule()
        )
        val contents = contentProcessor
            .getContent(book, chapter, content, includeTitle = false)
        scope.ensureActive()
        return ChapterProvider.getTextChapterAsync(
            scope, book, chapter, displayTitle, contents,
            simulatedChapterSize, reviewCountDeferred
        )
    }

    /** 对应原 contentLoadFinish 内 layoutChannel 的三种消费方式 */
    override suspend fun collectLayout(
        textChapter: TextChapterContract,
        offset: Int,
        upContent: Boolean,
        resetPageOffset: Boolean
    ) {
        val chapter = textChapter as? TextChapter
            ?: return super.collectLayout(textChapter, offset, upContent, resetPageOffset)
        val callBack = ReadBook.callBack
        when (offset) {
            0 -> {
                var available = false
                for (page in chapter.layoutChannel) {
                    val index = page.index
                    if (!available && page.containPos(ReadBook.durChapterPos)) {
                        if (upContent) {
                            callBack?.upContent(offset, resetPageOffset)
                        }
                        available = true
                    }
                    if (upContent && ReadBook.isScroll) {
                        if (max(index - 3, 0) < ReadBook.durPageIndex) {
                            callBack?.upContent(offset, false)
                        }
                    }
                    callBack?.onLayoutPageCompleted(index, page)
                }
                if (upContent) callBack?.upContent(offset, !available && resetPageOffset)
            }

            -1 -> {
                chapter.layoutChannel.receiveAsFlow().collect()
                if (upContent) callBack?.upContent(offset, resetPageOffset)
            }

            1 -> {
                for (page in chapter.layoutChannel) {
                    if (page.index > 1) {
                        continue
                    }
                    if (upContent) callBack?.upContent(offset, resetPageOffset)
                }
            }
        }
    }

    override fun recycleRecorders(beforeIndex: Int, afterIndex: Int) {
        if (!AppConfig.optimizeRender) {
            return
        }
        globalExecutor.execute {
            val textChapter = ReadBook.curTextChapter ?: return@execute
            if (afterIndex > beforeIndex) {
                textChapter.getPage(afterIndex - 2)?.recycleRecorders()
            }
            if (afterIndex < beforeIndex) {
                textChapter.getPage(afterIndex + 3)?.recycleRecorders()
            }
        }
    }
}
