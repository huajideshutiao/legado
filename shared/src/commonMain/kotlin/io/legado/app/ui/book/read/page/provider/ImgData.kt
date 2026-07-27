package io.legado.app.ui.book.read.page.provider

/**
 * 图片占位符信息（commonMain 跨端共用）。
 *
 * 原 TextChapterLayout 内部 `data class Img(src, style, onclick)`，
 * 下沉 commonMain 供 TextLayoutEngine 的 ColumnFactory 接口跨端引用。
 * app 端 TextChapterLayout 内部 Img 改为 typealias 引用本类（最小改动）。
 */
data class ImgData(val src: String, val style: String, val onclick: String)
