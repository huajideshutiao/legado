package io.legado.app.ui.book.read.page.entities

/**
 * 三章滑窗元素契约：[io.legado.app.model.ReadBookShared] 的章节编排只依赖这些成员。
 *
 * app 端 [TextChapter]（TextChapterLayout 异步排版）与 commonMain [TextChapterShared]
 * （SimpleChapterLayout 同步排版）都实现本接口，让下沉后的编排逻辑与排版实现解耦。
 */
interface TextChapterContract : TextChapterRef {

    /** 排版是否已完成 */
    val isCompleted: Boolean

    /** 末页页首在章节内的字符偏移 */
    val lastReadLength: Int

    /** 排版开始时段评数是否已就绪并用上；false 表示计数迟到需整章重排 */
    val reviewCountApplied: Boolean

    fun getPage(index: Int): TextPage?

    fun isLastIndex(index: Int): Boolean

    fun getReadLength(pageIndex: Int): Int

    fun getNextPageLength(length: Int): Int

    fun getPrevPageLength(length: Int): Int

    fun getPageIndexByCharIndex(charIndex: Int): Int

    fun clearSearchResult()

    /** 取消未完成的异步排版；同步排版实现无需处理 */
    fun cancelLayout() {}

    /** 通知排版侧当前页变化；同步排版实现无需处理 */
    fun notifyPageChanged() {}
}
