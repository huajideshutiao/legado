package io.legado.app.ui.book.read.page.entities.column

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isEpub
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlin.math.abs

/**
 * ImageColumn.refreshLayout 扩展函数（app 端实现，留在 android 模块）。
 *
 * 原方法重度依赖 android（Book/ImageProvider/BookHelp/ChapterProvider/ReadBook），
 * 不下沉 shared commonMain。ImageColumn 数据类已下沉 commonMain（见
 * `modules/shared/.../entities/column/ImageColumn.kt`），本扩展函数沿用原实现，
 * 调用方代码不变（仍走 `col.refreshLayout(book, isSingle)` 扩展函数语法）。
 */
suspend fun ImageColumn.refreshLayout(book: Book, isSingle: Boolean): Boolean {
    // 对于本地 EPUB，图片在 ZIP 内部，不需要检查磁盘缓存
    val isLocalEpub = book.isEpub && !book.origin.startsWith(BookType.webDavTag)
    if (!isLocalEpub && !io.legado.app.help.book.BookHelp.isImageExist(book, src)) return false

    val size = ImageProvider.getImageSize(book, src, ReadBook.bookSource)
    if (size.width <= 0 || size.height <= 0) return false

    val vh = ChapterProvider.visibleHeight.toFloat()
    val vw = ChapterProvider.visibleWidth.toFloat()
    val pt = ChapterProvider.paddingTop.toFloat()
    val pl = ChapterProvider.paddingLeft.toFloat()

    val scale = (vw / size.width).coerceAtMost(vh / size.height)
    val drawW = size.width * scale
    val drawH = size.height * scale

    val targetTop = if (isSingle) (vh - drawH) / 2f + pt else textLine.lineTop
    val targetStart = if (isSingle) (vw - drawW) / 2f + pl else this.start

    val deltaH = drawH - textLine.height
    if (abs(deltaH) > 0.5f || abs(textLine.lineTop - targetTop) > 0.5f || abs(this.start - targetStart) > 0.5f) {
        textLine.lineTop = targetTop
        textLine.lineBottom = targetTop + drawH
        this.start = targetStart
        this.end = targetStart + drawW

        if (!isSingle && abs(deltaH) > 0.5f) {
            val lines = textLine.textPage.lines
            // TextLine 是 data class, indexOf 的结构相等会命中内容相同的另一行, 必须按引用查
            val index = lines.indexOfFirst { it === textLine }
            if (index != -1) {
                for (i in index + 1 until lines.size) {
                    val l = lines[i]
                    l.lineTop += deltaH
                    l.lineBase += deltaH
                    l.lineBottom += deltaH
                }
            }
        }

        textLine.textPage.upRenderHeight()
        textLine.textPage.invalidate()
        return true
    }
    return false
}
