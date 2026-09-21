package io.legado.app.ui.book.read.page.entities.column

import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine

/**
 * 文字列
 *
 * @param drawOffsetX 绘制 X 偏移（px）：标点挤压裁左半的开始夹注标点（`“` `（` 等）字形需左移半字，
 *   列盒 [start]/[end] 已是裁半后的宽度，只有落笔位置要补这个偏移。只参与绘制，
 *   不影响命中盒与选中背景；未挤压的列为 0。
 */
data class TextColumn(
    override var start: Float,
    override var end: Float,
    val charData: String,
    val drawOffsetX: Float = 0f,
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine

}
