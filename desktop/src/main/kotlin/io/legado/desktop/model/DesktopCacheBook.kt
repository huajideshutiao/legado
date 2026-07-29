package io.legado.desktop.model

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.CacheBookCallback
import io.legado.app.model.CacheBookCallbacks
import io.legado.app.model.CacheBookShared
import io.legado.app.model.CacheBookShared.CacheBookModelShared
import io.legado.app.utils.postEvent
import kotlin.coroutines.CoroutineContext

/**
 * 桌面端 CacheBook 薄壳 (委托 [CacheBookShared])。
 *
 * # 背景
 *
 * 原本桌面端 [DesktopCacheBook] object 含完整章节预下载调度逻辑 (cacheBookMap /
 * getOrCreate / startProcessJob / CacheBookModel 等), 与 app 端 `CacheBook` 重复实现。
 * 现已把**非平台特有**的调度核心下沉到 shared commonMain [CacheBookShared],
 * 本 object 仅保留:
 *
 * 1. **桌面端 [CacheBookCallback] 注册**, 用 postEvent 通知阅读页 Composable 监听
 *    自行重载 (桌面端无 ReadBook 单例, 与 app 端 callback 桥接 ReadBook 区别)
 * 2. **对外 API 委托** [CacheBookShared], 保持桌面端调用点不变
 *
 * 调度核心逻辑 (cacheBookMap / getOrCreate / startProcessJob / downloadSummary /
 * CacheBookModel 等) 全部委托 [CacheBookShared], 行为与下沉前完全一致。
 *
 * # 平台差异 (对照 app 端 CacheBook)
 *
 * - **不依赖 CacheBookService (Android Service)**: 桌面端用协程直接处理,
 *   [ReadBookViewModelShared] 下载流程在协程内调 [startProcessJob]
 * - **不依赖 ReadBook 单例 (app 专属)**: app 端 callback 桥接
 *   `ReadBook.contentLoadFinish` / `downloadedChapters` / `downloadFailChapters`;
 *   桌面端 callback 用 postEvent 通知阅读页 Composable 监听自行重载
 *   ([DesktopCacheBookCallback.onContentLoadFinish] postEvent UP_DOWNLOAD + SAVE_CONTENT)
 * - **不下载图片 (桌面端无 BitmapFactory/BookHelp.saveImages)**: [CacheBookShared] 内
 *   调 `BookHelpProviders.get().saveImages` / `hasImageContent`, 桌面端
 *   [DesktopBookHelpAccessor] 用默认 no-op / false (与原 DesktopCacheBook 跳过图片一致)
 * - **hasContent 已缓存时跳过 saveImages**: 原 DesktopCacheBook 已缓存分支直接 onSuccess
 *   跳过; 下沉后 [CacheBookModelShared] 已缓存分支调 saveImages (桌面端 no-op) +
 *   getContent (返回正文), 行为等价 (仅多一次空 saveImages 调用)
 * - **addDownload clamp**: 原 DesktopCacheBook.addDownload 内部 clamp
 *   `min(end, book.lastChapterIndex)`; 下沉后 [CacheBookModelShared.addDownload] 不 clamp
 *   (与 app 端 CacheBookModel 一致, 由调用方负责 clamp)。
 *   [ReadBookViewModelShared] 下载调用前 clamp endIndex 到 lastChapterIndex
 *
 * # 已复用的 shared commonMain 下沉件
 *
 * - [CacheBookShared]: 调度核心 (本任务下沉)
 * - [CacheBookCallback] / [CacheBookCallbacks]: 回调抽象 (本任务下沉)
 * - [Coroutine.async] / [CompositeCoroutine]: shared help/coroutine 已下沉
 * - [ConcurrentException]: shared exception 已下沉
 * - [WebBook.getContentAwait]: shared model/webBook 已下沉
 * - [BookHelpProviders.get().saveContent / saveImages / hasImageContent / getContent]:
 *   shared help/book 已下沉 (saveImages/hasImageContent/getContent 本任务新增)
 * - [BookStorageProviders.get().hasContent]: 检测章节是否已缓存
 * - [onEachParallel] / [postEvent]: shared FlowExtensions / FlowBus 已下沉
 *
 * # 生命周期
 *
 * object 单例, 进程内全局共享 (与 app 端 CacheBook 一致)。
 * [close] 由 [ReadBookViewModelShared.onCleared] 调用清理所有任务 + 清空 map
 * (实际原桌面端注释提到"不调 close, 单例跨 VM 保留", 保持原行为, 由调用方决定)。
 */
object DesktopCacheBook {

    /** 对照原 DesktopCacheBook.cacheBookMap, 委托 [CacheBookShared.cacheBookMap] */
    val cacheBookMap get() = CacheBookShared.cacheBookMap

    /** 已成功下载的章节主键集合 (对照原 DesktopCacheBook.successDownloadSet) */
    val successDownloadSet get() = CacheBookShared.successDownloadSet

    /** 失败章节主键 -> 累计错误次数 (对照原 DesktopCacheBook.errorDownloadMap) */
    val errorDownloadMap get() = CacheBookShared.errorDownloadMap

    /** 对照原 DesktopCacheBook.getOrCreate(bookSource, book) */
    @Synchronized
    fun getOrCreate(bookSource: BookSource, book: Book): CacheBookModelShared =
        CacheBookShared.getOrCreate(bookSource, book)

    /** 对照原 DesktopCacheBook.close */
    fun close() = CacheBookShared.close()

    /** 对照原 DesktopCacheBook.setWorkingState */
    fun setWorkingState(value: Boolean) = CacheBookShared.setWorkingState(value)

    /** 对照原 DesktopCacheBook.startProcessJob */
    suspend fun startProcessJob(context: CoroutineContext) =
        CacheBookShared.startProcessJob(context)

    /** 下载摘要文案 (对照原 DesktopCacheBook.downloadSummary) */
    val downloadSummary: String get() = CacheBookShared.downloadSummary

    /** 下载进度 (已完成, 总数), 供通知使用 (对照原 DesktopCacheBook.downloadProgress) */
    val downloadProgress: Pair<Int, Int> get() = CacheBookShared.downloadProgress

    /** 是否有下载任务在跑 (对照原 DesktopCacheBook.isRun) */
    val isRun: Boolean get() = CacheBookShared.isRun

    /** 正在下载章节数 (对照原 DesktopCacheBook.onDownloadCount) */
    val onDownloadCount: Int get() = CacheBookShared.onDownloadCount

    /**
     * 注册桌面端 [CacheBookCallback], 用 postEvent 通知阅读页。
     *
     * 在 desktop main() 早期调用 (在任何 [CacheBookShared] 调用之前),
     * 与 `registerDesktopServiceLauncher` 同批次。
     */
    fun registerCallback() {
        CacheBookCallbacks.register(DesktopCacheBookCallback)
    }

    /**
     * 桌面端 [CacheBookCallback] 实现, 用 postEvent 通知阅读页 Composable。
     *
     * 对照原 [DesktopCacheBookModel.download] 成功分支:
     * `postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)` +
     * `postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))`
     *
     * 桌面端无 ReadBook 单例, [markDownloaded] / [markDownloadFailed] /
     * [markDownloadSuccess] no-op (与原 DesktopCacheBook 不维护 downloadedChapters 一致)。
     */
    private object DesktopCacheBookCallback : CacheBookCallback {
        // 桌面端无 ReadBook 单例, 下载状态标记 no-op
        override fun markDownloaded(chapterIndex: Int) {}
        override fun markDownloadFailed(chapterIndex: Int) {}
        override fun markDownloadSuccess(chapterIndex: Int) {}

        override fun onContentLoadFinish(
            book: Book,
            chapter: BookChapter,
            content: String,
            resetPageOffset: Boolean,
            canceled: Boolean
        ) {
            // 对照原 DesktopCacheBook.download 成功分支:
            // postEvent(UP_DOWNLOAD) + postEvent(SAVE_CONTENT) 通知阅读页重载
            // (app 端 callback 检查 ReadBook.book?.bookUrl 匹配才调 contentLoadFinish;
            //  桌面端无 ReadBook 单例, 直接 postEvent 让阅读页 Composable 监听自行重载)
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
            postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))
        }
    }
}
