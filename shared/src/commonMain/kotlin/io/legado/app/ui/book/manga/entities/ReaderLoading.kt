package io.legado.app.ui.book.manga.entities

data class ReaderLoading(
    override val chapterIndex: Int = 0,
    override val index: Int = 0,
    val mMessage: String? = null,
    val isVolume: Boolean = false
) : BaseMangaPage

/** 漫画图片单元格加载状态 (对照 app 端 render/MangaPageImageView.kt 同名枚举) */
enum class MangaCellState { LOADING, SUCCESS, ERROR }
