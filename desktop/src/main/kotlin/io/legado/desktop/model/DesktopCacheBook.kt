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
import io.legado.desktop.model.DesktopCacheBook.DesktopCacheBookCallback.markDownloadFailed
import io.legado.desktop.model.DesktopCacheBook.DesktopCacheBookCallback.markDownloadSuccess
import io.legado.desktop.model.DesktopCacheBook.DesktopCacheBookCallback.markDownloaded
import io.legado.desktop.model.DesktopCacheBook.close
import kotlin.coroutines.CoroutineContext

/**
 * 桌面端 CacheBook 薄壳 (委托 [CacheBookShared])。
 *
 * 原桌面端 DesktopCacheBook 含完整调度逻辑 (cacheBookMap/getOrCreate/startProcessJob/
 * CacheBookModel 等), 与 app 端 CacheBook 重复; 调度核心已下沉 [CacheBookShared],
 * 本 object 仅保留: ① 桌面端 [CacheBookCallback] 注册 (postEvent 通知阅读页自行重载,
 * 桌面无 ReadBook 单例); ② 对外 API 委托, 调用点不变。
 *
 * 平台差异 (对照 app 端 CacheBook):
 * - 无 CacheBookService: 协程直接处理, ReadBookViewModelShared 在协程内调 startProcessJob
 * - 不下载图片: BookHelpProviders.saveImages 桌面端 no-op/false
 * - hasContent 已缓存分支仍调 saveImages (no-op) + getContent, 行为等价 (仅多一次空调用)
 * - addDownload 不 clamp (与 app 端一致, 由调用方负责; ReadBookViewModelShared 下载前
 *   clamp endIndex 到 lastChapterIndex)
 *
 * 生命周期: object 单例; [close] 由 ReadBookViewModelShared.onCleared 调用
 * (原注释提到"不调 close, 单例跨 VM 保留", 保持原行为, 由调用方决定)。
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
