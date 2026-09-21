package io.legado.app.ui.book.read.page.entities

import io.legado.app.ui.book.read.page.entities.TextPage.Companion.emptyTextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn

/**
 * 行信息
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
data class TextLine(
    var text: String = "",
    private val textColumns: ArrayList<BaseColumn> = arrayListOf(),
    var lineTop: Float = 0f,
    var lineBase: Float = 0f,
    var lineBottom: Float = 0f,
    var indentWidth: Float = 0f,
    var paragraphNum: Int = 0,
    var chapterPosition: Int = 0,
    var pagePosition: Int = 0,
    val isTitle: Boolean = false,
    var isParagraphEnd: Boolean = false,
    var isImage: Boolean = false,
    var startX: Float = 0f,
    var indentSize: Int = 0,
    var exceed: Boolean = false,
    var onlyTextColumn: Boolean = true,
) {

    val columns: List<BaseColumn> get() = textColumns
    val charSize: Int get() = text.length

    /** 零列行（消息页文案里的空行）无几何可取，行首尾归 0 */
    val lineStart: Float get() = textColumns.firstOrNull()?.start ?: 0f
    val lineEnd: Float get() = textColumns.lastOrNull()?.end ?: 0f
    val chapterIndices: IntRange get() = chapterPosition..chapterPosition + charSize
    val height: Float inline get() = lineBottom - lineTop

    var textPage: TextPage = emptyTextPage
    var isLeftLine = true

    fun addColumn(column: BaseColumn) {
        if (column !is TextColumn) {
            onlyTextColumn = false
        }
        column.textLine = this
        textColumns.add(column)
    }

    fun getColumn(index: Int): BaseColumn {
        return textColumns[index]
    }

    fun getColumnReverseAt(index: Int, offset: Int = 0): BaseColumn {
        return textColumns[textColumns.lastIndex - offset - index]
    }

    fun getColumnsCount(): Int {
        return textColumns.size
    }

    fun isTouch(x: Float, y: Float, relativeOffset: Float): Boolean {
        return y > lineTop + relativeOffset
                && y < lineBottom + relativeOffset
                && x >= lineStart
                && x <= lineEnd
    }

    fun isTouchY(y: Float, relativeOffset: Float): Boolean {
        return y > lineTop + relativeOffset
                && y < lineBottom + relativeOffset
    }

    fun isVisible(relativeOffset: Float): Boolean {
        val top = lineTop + relativeOffset
        val bottom = lineBottom + relativeOffset
        val width = bottom - top
        // 几何参数从所属 TextPage 读取（由排版层在 onPageCompleted 时注入），
        // 数据化前由 ChapterProvider 静态字段提供。
        val visibleTop = textPage.paddingTop
        val visibleBottom = textPage.visibleBottom
        val visible = when {
            // 完全可视
            top >= visibleTop && bottom <= visibleBottom -> true
            top <= visibleTop && bottom >= visibleBottom -> true
            // 上方第一行部分可视
            top < visibleTop && bottom > visibleTop && bottom < visibleBottom -> {
                if (isImage) {
                    true
                } else {
                    val visibleRate = (bottom - visibleTop) / width
                    visibleRate > 0.6
                }
            }
            // 下方第一行部分可视
            top > visibleTop && top < visibleBottom && bottom > visibleBottom -> {
                if (isImage) {
                    true
                } else {
                    val visibleRate = (visibleBottom - top) / width
                    visibleRate > 0.6
                }
            }
            // 不可视
            else -> false
        }
        return visible
    }

    companion object {
        val emptyTextLine = TextLine()
    }

}
