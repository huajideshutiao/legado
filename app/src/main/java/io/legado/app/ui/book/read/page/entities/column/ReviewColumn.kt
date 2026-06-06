package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.annotation.Keep
import androidx.core.graphics.createBitmap
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * 段评气泡列
 * 形状参照设计稿：胶囊气泡 + 左下小尾巴 + 居中数字
 * 外圈（气泡 + 尾巴）按 size+color 缓存到 ALPHA_8 bitmap，全章复用，每帧只重画数字
 */
@Keep
data class ReviewColumn(
    override var start: Float,
    override var end: Float,
    val paragraphIndex: Int = 0,
    var count: Int = 0
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine

    override fun draw(view: ContentTextView, canvas: Canvas) {
        if (count == 0) return
        // 容器 = column [start, end] × textLine.height（与 ImageColumn 同款容器）
        val containerW = end - start
        val containerH = textLine.height
        val scale = minOf(containerW / ICON_W, containerH / ICON_H) * FILL_RATIO
        val iconW = ICON_W * scale
        val iconH = ICON_H * scale
        val left = start + (containerW - iconW) / 2f
        val top = (containerH - iconH) / 2f

        val paint = ChapterProvider.reviewPaint
        // 外圈复用缓存 bitmap（ALPHA_8 + drawBitmap 传 paint 上色，省 3/4 内存）
        // bitmap 含 padding 防 stroke 被边界裁；绘制时反向偏移 padding
        val pad = kotlin.math.ceil(scale).toInt()
        val bmp = getOutlineBitmap(iconW, iconH, scale, pad)
        paint.style = Paint.Style.FILL
        canvas.drawBitmap(bmp, left - pad, top - pad, paint)

        // 仅数字按 count 重画
        paint.textSize = 12f * scale
        val fm = paint.fontMetrics
        canvas.drawText(
            countText,
            left + 12.5f * scale,
            top + 10f * scale - (fm.ascent + fm.descent) / 2f,
            paint
        )
    }

    val countText by lazy {
        if (count > 99) "99+" else count.toString()
    }

    companion object {
        private const val ICON_W = 25f
        private const val ICON_H = 29f
        private const val FILL_RATIO = 0.95f

        // 全章/全应用共享一份外圈 bitmap：同字号同 size 下，所有气泡复用
        // size 变化（字号、布局）→ 重新渲染并替换缓存
        @Volatile
        private var cachedBitmap: Bitmap? = null
        private var cachedW: Int = 0
        private var cachedH: Int = 0

        @Synchronized
        private fun getOutlineBitmap(iconW: Float, iconH: Float, scale: Float, pad: Int): Bitmap {
            // bitmap 尺寸 = ceil(icon) + 两侧 padding，留出 stroke 外缘空间
            val w = (kotlin.math.ceil(iconW).toInt() + 2 * pad).coerceAtLeast(1)
            val h = (kotlin.math.ceil(iconH).toInt() + 2 * pad).coerceAtLeast(1)
            val cached = cachedBitmap
            if (cached != null && !cached.isRecycled && cachedW == w && cachedH == h) {
                return cached
            }
            val bmp = createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            val p = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 2f * scale
            }
            val path = Path()
            val arc = RectF()

            // 本地坐标 → bitmap 坐标（icon 左上角偏移 pad，给四周 stroke 留空间）
            fun mx(v: Float) = pad + v * scale
            fun my(v: Float) = pad + v * scale
            // 顶 → 左半弧 → 尾巴 → 右半弧 → close 回顶（与 SVG 一条不闭合 path 等价）
            path.moveTo(mx(10f), my(0f))
            arc.set(mx(0f), my(0f), mx(20f), my(20f))
            path.arcTo(arc, 270f, -180f, false)
            path.cubicTo(mx(7f), my(29f), mx(11f), my(24f), mx(15f), my(20f))
            arc.set(mx(5f), my(0f), mx(25f), my(20f))
            path.arcTo(arc, 90f, -180f, false)
            path.close()
            c.drawPath(path, p)

            cached?.recycle()
            cachedBitmap = bmp
            cachedW = w
            cachedH = h
            return bmp
        }
    }
}
