package io.legado.app.ui.book.read.page.entities.column

import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import kotlin.jvm.JvmField

/**
 * 图片列 - 数据部分（已下沉 shared commonMain）。
 *
 * `refreshLayout` 原方法重度依赖 android（Book/ImageProvider/BookHelp/ChapterProvider/ReadBook），
 * 不下沉，已抽出为 app 端扩展函数 `ImageColumn.refreshLayout(book, isSingle)`（见
 * `app/.../entities/column/ImageColumnExt.kt`）。调用方代码不变（仍走扩展函数语法）。
 */
data class ImageColumn(
    override var start: Float,
    override var end: Float,
    var src: String,
    var onClick: String = ""
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine

    /** 渲染侧绘图缓存（Bitmap/RectF 等 android 类型），由 ColumnRender 持有与更新 */
    @JvmField
    var renderCache: Any? = null

}
