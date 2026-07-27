package io.legado.app.ui.book.read.page.entities.column

import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine

/**
 * 段评气泡列
 */
data class ReviewColumn(
    override var start: Float,
    override var end: Float,
    val paragraphIndex: Int = 0,
    var count: Int = 0
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine

    val countText by lazy {
        if (count > 99) "99+" else count.toString()
    }

}
