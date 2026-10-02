package io.legado.app.ui.book.manga.entities

data class MangaContent(
    /** 批次归属书籍, 来自产出 [items] 的章节快照 (非发布时另读); 空串 = 无章节的空批次 */
    val bookUrl: String,
    /** 发布时的装载代际, 与 [bookUrl] 同一快照; 定位请求只消费不早于自身代际的批次 */
    val generation: Int,
    val pos: Int,
    val items: List<Any>,
    val curFinish: Boolean,
    val nextFinish: Boolean
)
