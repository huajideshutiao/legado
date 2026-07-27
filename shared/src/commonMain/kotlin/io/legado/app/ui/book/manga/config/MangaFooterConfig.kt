package io.legado.app.ui.book.manga.config

// INFO_BAR_ALIGN_LEFT=0 原 app 端 render 包常量, render 未下沉, 此处用字面量保持默认值一致
data class MangaFooterConfig(
    var hideChapterLabel: Boolean = false,
    var hideChapter: Boolean = false,
    var hidePageNumberLabel: Boolean = false,
    var hidePageNumber: Boolean = false,
    var hideProgressRatioLabel: Boolean = false,
    var hideProgressRatio: Boolean = false,
    var footerOrientation: Int = 0,//默认靠左 (对应 app 端 INFO_BAR_ALIGN_LEFT=0)
    var hideFooter: Boolean = false,
    var hideChapterName: Boolean = false,
)
