package io.legado.app.ui.book.read.page.provider

/**
 * 排版平台回调接口（app 端实现注入）。
 *
 * 把 TextLayoutEngine 中依赖 android 平台的行为（协程取消检查、页面完成回调）抽象出来，
 * 让 commonMain 的 TextLayoutEngine 不直接依赖 android CoroutineScope/notifyPageChanged 等。
 *
 * app 端 TextChapterLayout 实现本接口，回调时执行：
 * - ensureActive: `currentCoroutineContext().ensureActive()`
 * - onPageCompleted: 触发 notifyPageChanged/channel.send/listener.onLayoutPageCompleted 等
 */
interface TextLayoutCallback {

    /**
     * 协程取消检查。commonMain 可用 kotlinx.coroutines，但 currentCoroutineContext() 是 suspend，
     * 通过回调让 app 端在协程上下文中调用 ensureActive()。
     */
    suspend fun ensureActive()

    /**
     * 页面完成回调。app 端执行：textPages.add/page.index 设置/upLinesPosition/upRenderHeight/
     * channel.trySend/listener.onLayoutPageCompleted 等。
     *
     * TextLayoutEngine 在 prepareNextPageIfNeed 中触发本回调。
     */
    fun onPageCompleted()
}
