package io.legado.app.ui.book.read.page.entities.column

import io.legado.app.ui.book.read.page.entities.TextLine

/**
 * 列基类
 */
interface BaseColumn {
    var start: Float
    var end: Float
    var textLine: TextLine

    fun isTouch(x: Float): Boolean {
        return x > start && x < end
    }

}
