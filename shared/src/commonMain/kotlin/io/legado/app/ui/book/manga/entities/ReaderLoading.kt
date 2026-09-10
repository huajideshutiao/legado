package io.legado.app.ui.book.manga.entities

data class ReaderLoading(
    override val chapterIndex: Int = 0,
    override val index: Int = 0,
    val mMessage: String? = null,
    val isVolume: Boolean = false
) : BaseMangaPage

/** 漫画图片单元格加载状态 (供 MangaCoilImage 等漫画图片渲染槽使用) */
enum class MangaCellState { LOADING, SUCCESS, ERROR }
