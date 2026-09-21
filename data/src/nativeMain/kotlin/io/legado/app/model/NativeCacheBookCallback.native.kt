package io.legado.app.model

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.postEvent

/**
 * native (iOS/鸿蒙) [CacheBookCallback] 最小实现, 对照 desktop DesktopCacheBookCallback。
 *
 * 未注册时 [CacheBookShared] 内 CacheBookCallbacks.get() 直接 error(), NativeServiceLauncher /
 * UpdateBookShared 驱动缓存流程即崩。native 端无 ReadBook 单例, mark* 走接口默认 no-op,
 * onContentLoadFinish 用 postEvent 通知阅读页 Composable 监听自行重载。
 */
private object NativeCacheBookCallback : CacheBookCallback {

    override fun onContentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        resetPageOffset: Boolean,
        canceled: Boolean
    ) {
        postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))
    }
}

/** iOS/鸿蒙宿主启动早期注册一次 (任何 CacheBookShared 调用之前)。 */
fun registerNativeCacheBookCallback() {
    CacheBookCallbacks.register(NativeCacheBookCallback)
}
