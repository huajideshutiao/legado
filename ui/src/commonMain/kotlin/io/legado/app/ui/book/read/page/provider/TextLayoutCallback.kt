package io.legado.app.ui.book.read.page.provider

/**
 * 排版逐页回调：[PaginationEngine.paginate] 每切完一页触发，让调用方边排边上屏。
 * 目前没有实现方（唯一调用方 [SimpleChapterLayout] 不传），保留待流式排版接入。
 */
interface TextLayoutCallback {

    /** 协程取消检查：`currentCoroutineContext().ensureActive()` 是 suspend，交由实现方在自己上下文里调。 */
    suspend fun ensureActive()

    /** 一页切完（几何与字符账本已写全）。 */
    fun onPageCompleted()
}
