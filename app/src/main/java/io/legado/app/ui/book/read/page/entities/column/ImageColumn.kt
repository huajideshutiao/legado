package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.annotation.Keep
import io.legado.app.data.entities.Book
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import kotlin.math.abs

/**
 * 图片列 - 修复快速翻页裁剪与点击翻页拦截问题
 */
@Keep
data class ImageColumn(
    override var start: Float,
    override var end: Float,
    var src: String,
    var onClick: String = ""
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine

    private var lastBitmap: Bitmap? = null
    private val cachedRectF = RectF()
    private var lastContainerW = -1f
    private var lastContainerH = -1f

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val book = ReadBook.book ?: return
        val isSingle = book.getImageStyle().equals(Book.imgStyleSingle, true)

        val containerW = end - start
        val containerH = textLine.height
        val bitmap = ImageProvider.getImage(book, src, containerW.toInt(), containerH.toInt())

        // 仅更新绘图缓存，严禁在此处同步修改 textLine 坐标
        if (bitmap !== lastBitmap || containerW != lastContainerW || containerH != lastContainerH) {
            // 如果在绘制时发现是真图（非占位图）且尺寸未对齐，提交异步布局更新请求
            if (bitmap != ImageProvider.errorBitmap && (abs(bitmap.width - containerW) > 1f || isSingle)) {
                view.post {
                    // 在下一帧安全调用，避开 CanvasRecorder 录制期
                    io.legado.app.help.coroutine.Coroutine.async {
                        refreshLayout(book, isSingle)
                        view.postInvalidate()
                    }
                }
            }
            updateDrawCache(bitmap, containerW, containerH)
        }

        kotlin.runCatching {
            canvas.drawBitmap(bitmap, null, cachedRectF, view.imagePaint)
        }.onFailure { e ->
            appCtx.toastOnUi(e.localizedMessage)
        }
    }

    /**
     * 后台/异步布局刷新：下载完成后调用，确保坐标与真图匹配
     */
    suspend fun refreshLayout(book: Book, isSingle: Boolean): Boolean {
        if (!io.legado.app.help.book.BookHelp.isImageExist(book, src)) return false

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
                val index = lines.indexOf(textLine)
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

    private fun updateDrawCache(bitmap: Bitmap, containerW: Float, containerH: Float) {
        val bW = bitmap.width.toFloat()
        val bH = bitmap.height.toFloat()
        val drawScale = (containerW / bW).coerceAtMost(containerH / bH)

        val finalW = bW * drawScale
        val finalH = bH * drawScale
        val offsetX = (containerW - finalW) / 2f
        val offsetY = (containerH - finalH) / 2f

        cachedRectF.set(start + offsetX, offsetY, start + offsetX + finalW, offsetY + finalH)

        lastBitmap = bitmap
        lastContainerW = containerW
        lastContainerH = containerH
    }
}
