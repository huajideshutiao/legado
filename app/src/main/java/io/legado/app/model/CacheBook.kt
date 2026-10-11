package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

/**
 * app 端 CacheBook 薄壳 (委托 [CacheBookShared])。
 *
 * 本 object 只保留 app 端独有的一件事: [registerCallback] —— 桥接活动阅读页
 * ([ActiveReadBookRegistry]), 由 App.onCreate 调用。
 *
 * 其余读写 (map / 计数 / 摘要 / 关闭 / 调度) 一律经 [CacheBookShared];
 * 缓存服务的启停不经本 object (调用方直接走 [io.legado.app.help.service.ServiceLaunchers])。
 */
object CacheBook {

    /** 按 bookUrl 索引的下载模型 map (委托 [CacheBookShared.cacheBookMap]) */
    val cacheBookMap get() = CacheBookShared.cacheBookMap

    /** 停止全部下载任务并清空下载池 (委托 [CacheBookShared.close]) */
    fun close() = CacheBookShared.close()

    /** 启动下载处理 job 并等待它跑完 (委托 [CacheBookShared.startProcessJob]) */
    suspend fun startProcessJob() = CacheBookShared.startProcessJob()

    /** 下载摘要文案 (供通知正文使用) */
    val downloadSummary: String get() = CacheBookShared.downloadSummary

    /** 下载进度 (已完成, 总数), 供通知进度条使用 */
    val downloadProgress: Pair<Int, Int> get() = CacheBookShared.downloadProgress

    /** 是否有下载任务在跑 */
    val isRun: Boolean get() = CacheBookShared.isRun

    /**
     * 注册 [CacheBookCallback] 桥接活动阅读页。
     *
     * 在 App.onCreate 早期调用 (在任何 [CacheBookShared] 调用之前),
     * 与 `registerAndroidServiceLauncher` 同批次。
     *
     * [CacheBookCallback.onContentLoadFinish] 经 [ActiveReadBookRegistry] 找到活动阅读实例,
     * 当前章正文下载完成时重载该章 (对照共享层的下载完成回调 → `ReadBook.contentLoadFinish`)。
     * 下载计数标记走接口默认 no-op: 阅读器的
     * downloadedChapters / downloadFailChapters 由 ReadBookViewModelShared 自持并直接同步
     * [CacheBookShared.errorDownloadMap], 无需回调转写。
     */
    fun registerCallback() {
        CacheBookCallbacks.register(AppCacheBookCallback)
    }

    /**
     * app 端 [CacheBookCallback] 实现, 桥接活动阅读页 (对照桌面 DesktopCacheBookCallback)。
     */
    private object AppCacheBookCallback : CacheBookCallback {

        override fun onContentLoadFinish(
            book: Book,
            chapter: BookChapter,
            content: String,
            resetPageOffset: Boolean,
            canceled: Boolean
        ) {
            if (canceled) return
            val readBook = ActiveReadBookRegistry.current ?: return
            if (readBook.bookValue?.bookUrl != book.bookUrl) return
            // 只处理当前章 (对照原版 contentLoadFinish 的 offset 0 分支: 同章重载保留 durChapterPos);
            // 前后章由切章时的 launchChapterLoad 补载, 这里调 loadChapter 会把阅读器跳走
            if (chapter.index != readBook.durChapterIndexValue) return
            ActiveReadBookRegistry.currentViewModel
                ?.loadChapter(chapter.index, keepScrollOffset = !resetPageOffset)
        }
    }
}
