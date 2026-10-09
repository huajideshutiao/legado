package io.legado.desktop.model

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.CacheBookCallback
import io.legado.app.model.CacheBookCallbacks
import io.legado.app.model.CacheBookShared
import io.legado.app.utils.postEvent

/**
 * 桌面端 CacheBook 薄壳 (委托 [CacheBookShared])。
 *
 * 本 object 只保留桌面端独有的两件事:
 * 1. [registerCallback] —— 桌面无 ReadBook 单例, 用 postEvent 通知阅读页自行重载
 * 2. [isRun] —— 供 [DesktopReadBookPlatform.isCacheBookServiceRun] 取运行态
 *
 * 其余读写与调度一律经 [CacheBookShared]。
 */
object DesktopCacheBook {

    /** 是否有下载任务在跑 */
    val isRun: Boolean get() = CacheBookShared.isRun

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
     * 桌面端 [CacheBookCallback] 实现: 章节正文下载完成后用 postEvent 通知阅读页 Composable
     * (`EventBus.UP_DOWNLOAD` + `EventBus.SAVE_CONTENT`)。
     */
    private object DesktopCacheBookCallback : CacheBookCallback {

        override fun onContentLoadFinish(
            book: Book,
            chapter: BookChapter,
            content: String,
            resetPageOffset: Boolean,
            canceled: Boolean
        ) {
            // postEvent(UP_DOWNLOAD) + postEvent(SAVE_CONTENT) 通知阅读页重载
            // (app 端 callback 检查 ReadBook.book?.bookUrl 匹配才调 contentLoadFinish;
            //  桌面端无 ReadBook 单例, 直接 postEvent 让阅读页 Composable 监听自行重载)
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
            postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))
        }
    }
}
