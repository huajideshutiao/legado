package io.legado.app.ui.book.read.page.entities.column

import androidx.annotation.Keep
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine


/**
 * 按钮列
 */
@Keep
data class ButtonColumn(
    override var start: Float,
    override var end: Float,
) : BaseColumn {
    override var textLine: TextLine = emptyTextLine
}
