package io.legado.app.model

import android.content.Context
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.service.ServiceLaunchers
import io.legado.app.model.CacheBookShared.CacheBookModelShared
import io.legado.app.service.CacheBookService
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.runBlocking

/**
 * app 端 CacheBook 薄壳 (委托 [CacheBookShared])。
 *
 * # 背景
 *
 * 原本 app 端 [CacheBook] object 含完整章节预下载调度逻辑 (cacheBookMap / getOrCreate /
 * startProcessJob / CacheBookModel 等), 与桌面端 `DesktopCacheBook` 重复实现。
 * 现已把**非 Android 特有**的调度核心下沉到 shared commonMain [CacheBookShared],
 * 本 object 仅保留:
 *
 * 1. **Android 特有入口** (依赖 Context + Service): [start] / [remove] / [stop],
 *    通过 [ServiceLaunchers] 启 [CacheBookService] (与原 `context.startService<CacheBookService>`
 *    行为一致, 仅多一层 provider 间接)
 * 2. **[CacheBookCallback] 注册**, 桥接 [ReadBook] 单例 (contentLoadFinish /
 *    downloadedChapters / downloadFailChapters), 在 App.onCreate 调 [registerCallback]
 *
 * 调度核心逻辑 (cacheBookMap / getOrCreate / startProcessJob / downloadSummary /
 * CacheBookModel 等) 全部委托 [CacheBookShared], 行为与下沉前完全一致。
 *
 * # 注册时机
 *
 * [CacheBookCallbacks.register] 由 App.onCreate 调 [registerCallback] 完成
 * (在 [CacheBookShared] 任何调用之前), 与 `registerAndroidServiceLauncher` 同批次。
 *
 * # 类型兼容
 *
 * 原 `CacheBook.CacheBookModel` 内部类已下沉为 [CacheBookModelShared], 本文件提供
 * [CacheBookModel] typealias 保持源码兼容 ([CacheBookService] / [ReadBook] /
 * [MainViewModel] 等对 `CacheBookModel` 的引用不变)。
 */
object CacheBook {

    /** 对照 app 端原 CacheBook.cacheBookMap, 委托 [CacheBookShared.cacheBookMap] */
    val cacheBookMap get() = CacheBookShared.cacheBookMap

    /** 已成功下载的章节主键集合 (对照 app 端原 CacheBook.successDownloadSet) */
    val successDownloadSet get() = CacheBookShared.successDownloadSet

    /** 失败章节主键 -> 累计错误次数 (对照 app 端原 CacheBook.errorDownloadMap) */
    val errorDownloadMap get() = CacheBookShared.errorDownloadMap

    /**
     * 对照 app 端原 CacheBook.getOrCreate(bookUrl)。
     * 被包裹方是 suspend (DB 查询), 调用方 (CacheBookService) 为同步签名, 保留 runBlocking
     * (原版就是 @Synchronized 内阻塞 DAO 查询, 语义一致)。
     */
    @Synchronized
    fun getOrCreate(bookUrl: String): CacheBookModel? = runBlocking { CacheBookShared.getOrCreate(bookUrl) }

    /** 对照 app 端原 CacheBook.getOrCreate(bookSource, book); 被包裹方非 suspend, 直接透传 (阅读热路径, 免 runBlocking 开销) */
    @Synchronized
    fun getOrCreate(bookSource: BookSource, book: Book): CacheBookModel =
        CacheBookShared.getOrCreate(bookSource, book)

    /** 对照 app 端原 CacheBook.close */
    fun close() = CacheBookShared.close()

    /** 对照 app 端原 CacheBook.setWorkingState */
    fun setWorkingState(value: Boolean) = CacheBookShared.setWorkingState(value)

    /** 对照 app 端原 CacheBook.startProcessJob */
    suspend fun startProcessJob(context: CoroutineContext) =
        CacheBookShared.startProcessJob(context)

    /** 对照 app 端原 CacheBook.downloadSummary */
    val downloadSummary: String get() = CacheBookShared.downloadSummary

    /** 对照 app 端原 CacheBook.downloadProgress */
    val downloadProgress: Pair<Int, Int> get() = CacheBookShared.downloadProgress

    /** 对照 app 端原 CacheBook.isRun */
    val isRun: Boolean get() = CacheBookShared.isRun

    /** 对照 app 端原 CacheBook.onDownloadCount */
    val onDownloadCount: Int get() = CacheBookShared.onDownloadCount

    /**
     * 启动缓存书籍服务 (Android 特有, 依赖 Context + Service)。
     *
     * 对照 app 端原 `CacheBook.start(context, book, start, end)`,
     * 走 [ServiceLaunchers.get] 启 [CacheBookService] (action=start),
     * 行为与原 `context.startService<CacheBookService>` 完全一致。
     *
     * 本地书不预下载 (与原 `if (!book.isLocal)` 检查一致)。
     */
    fun start(context: Context, book: Book, start: Int, end: Int) {
        if (!book.isLocal) {
            ServiceLaunchers.get().startCacheBookService(book.bookUrl, start, end)
        }
    }

    /**
     * 移除单本书的缓存下载任务 (Android 特有, 依赖 Context + Service)。
     *
     * 对照 app 端原 `CacheBook.remove(context, bookUrl)`,
     * 走 [ServiceLaunchers.get] 启 [CacheBookService] (action=remove)。
     */
    fun remove(context: Context, bookUrl: String) {
        ServiceLaunchers.get().removeCacheBookService(bookUrl)
    }

    /**
     * 停止缓存书籍服务 (Android 特有, 依赖 Context + Service)。
     *
     * 对照 app 端原 `CacheBook.stop(context)`,
     * 走 [ServiceLaunchers.get] 启 [CacheBookService] (action=stop)。
     * 原 app 端检查 `CacheBookService.isRun` 才启 Service, 这里保留检查避免无谓 startService。
     */
    fun stop(context: Context) {
        if (CacheBookService.isRun) {
            ServiceLaunchers.get().stopCacheBookService()
        }
    }

    /**
     * 注册 [CacheBookCallback] 桥接 [ReadBook] 单例。
     *
     * 在 App.onCreate 早期调用 (在任何 [CacheBookShared] 调用之前),
     * 与 `registerAndroidServiceLauncher` 同批次。
     *
     * 桥接关系 (对照 app 端原 CacheBookModel 内部对 ReadBook 的直接引用):
     * - [CacheBookCallback.markDownloaded] → `ReadBook.downloadedChapters.add(index)`
     * - [CacheBookCallback.markDownloadFailed] → `ReadBook.downloadFailChapters[index]++`
     * - [CacheBookCallback.markDownloadSuccess] → `ReadBook.downloadFailChapters.remove(index)`
     * - [CacheBookCallback.onContentLoadFinish] → 检查 `ReadBook.book?.bookUrl == book.bookUrl`
     *   才调 `ReadBook.contentLoadFinish(...)` (与原 CacheBookModel.downloadFinish 一致)
     */
    fun registerCallback() {
        CacheBookCallbacks.register(AppCacheBookCallback)
    }

    /**
     * app 端 [CacheBookCallback] 实现, 桥接 [ReadBook] 单例。
     *
     * 对照 app 端原 [CacheBookModel] 内部对 [ReadBook] 的直接引用,
     * 把下沉到 commonMain 的 [CacheBookShared.CacheBookModelShared] 回调到 app 端阅读流。
     */
    private object AppCacheBookCallback : CacheBookCallback {
        override fun markDownloaded(chapterIndex: Int) {
            ReadBook.downloadedChapters.add(chapterIndex)
        }

        override fun markDownloadFailed(chapterIndex: Int) {
            ReadBook.downloadFailChapters[chapterIndex] =
                (ReadBook.downloadFailChapters[chapterIndex] ?: 0) + 1
        }

        override fun markDownloadSuccess(chapterIndex: Int) {
            // 对照 app 端 CacheBookModel.downloadAwait / download(scope, chapter, ...) 成功分支:
            // ReadBook.downloadFailChapters.remove(chapter.index)
            // 成功后清失败计数, 避免下次失败时累计旧值
            ReadBook.downloadFailChapters.remove(chapterIndex)
        }

        override fun onContentLoadFinish(
            book: Book,
            chapter: BookChapter,
            content: String,
            resetPageOffset: Boolean,
            canceled: Boolean
        ) {
            // 对照 app 端 CacheBookModel.downloadFinish:
            // 检查当前阅读书 == 下载书才调 ReadBook.contentLoadFinish
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.contentLoadFinish(
                    book, chapter, content,
                    resetPageOffset = resetPageOffset,
                    canceled = canceled
                )
            }
        }
    }
}

/**
 * app 端 [CacheBookModelShared] 别名, 兼容现有 `CacheBookModel` 引用。
 *
 * 原 app 端 `CacheBook.CacheBookModel` 类已下沉到 [CacheBookShared.CacheBookModelShared],
 * 本 typealias 保持源码兼容 (内部 `getOrCreate(...): CacheBookModel` 返回类型 +
 * [CacheBookService] / [ReadBook] / [MainViewModel] 等对 `CacheBookModel` 的推断类型不变)。
 */
typealias CacheBookModel = CacheBookModelShared
