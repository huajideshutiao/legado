package io.legado.app.ui.book.read.page.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import androidx.core.graphics.createBitmap
import io.legado.app.data.entities.Book
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.entities.column.refreshLayout
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * Column 渲染侧（android 绘制行为）。
 * 数据模型 column 包保持纯 Kotlin，Canvas/Bitmap/Paint 绘制集中于此。
 */

fun BaseColumn.draw(view: ContentTextView, canvas: Canvas) {
    when (this) {
        is TextColumn -> drawTextColumn(view, canvas)
        is ImageColumn -> drawImageColumn(view, canvas)
        is ReviewColumn -> drawReviewColumn(canvas)
        else -> Unit // ButtonColumn 等无绘制
    }
}

private fun TextColumn.drawTextColumn(view: ContentTextView, canvas: Canvas) {
    val textPaint = if (textLine.isTitle) {
        ChapterProvider.titlePaint
    } else {
        ChapterProvider.contentPaint
    }
    val textColor = if (textLine.isReadAloud || isSearchResult) {
        ThemeStore.accentColor
    } else {
        ReadBookConfig.textColor
    }
    if (textPaint.color != textColor) {
        textPaint.color = textColor
    }
    val y = textLine.lineBase - textLine.lineTop
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val letterSpacing = textPaint.letterSpacing * textPaint.textSize
        val letterSpacingHalf = letterSpacing * 0.5f
        canvas.drawText(charData, start + letterSpacingHalf, y, textPaint)
    } else {
        canvas.drawText(charData, start, y, textPaint)
    }
    if (selected) {
        canvas.drawRect(start, 0f, end, textLine.height, view.selectedPaint)
    }
}

/**
 * ImageColumn 绘图缓存（android 类型，随渲染侧存放）。
 */
private class ImageDrawCache {
    var lastBitmap: Bitmap? = null
    val cachedRectF = RectF()
    var lastContainerW = -1f
    var lastContainerH = -1f
}

private val ImageColumn.drawCache: ImageDrawCache
    get() = (renderCache as? ImageDrawCache) ?: ImageDrawCache().also { renderCache = it }

private fun ImageColumn.drawImageColumn(view: ContentTextView, canvas: Canvas) {
    val book = ReadBook.book ?: return
    val isSingle = book.config.imageStyle.equals(Book.imgStyleSingle, true)

    val containerW = end - start
    val containerH = textLine.height
    val bitmap = ImageProvider.getImage(book, src, containerW.toInt(), containerH.toInt())

    val cache = drawCache
    // 仅更新绘图缓存，严禁在此处同步修改 textLine 坐标
    if (bitmap !== cache.lastBitmap || containerW != cache.lastContainerW || containerH != cache.lastContainerH) {
        // 如果在绘制时发现是真图（非占位图）且尺寸未对齐，提交异步布局更新请求
        if (isSingle && bitmap != ImageProvider.errorBitmap) {
            view.post {
                // 在下一帧安全调用，避开 CanvasRecorder 录制期
                io.legado.app.help.coroutine.Coroutine.async {
                    refreshLayout(book, isSingle)
                    view.postInvalidate()
                }
            }
        }
        cache.updateDrawCache(start, bitmap, containerW, containerH)
    }

    kotlin.runCatching {
        canvas.drawBitmap(bitmap, null, cache.cachedRectF, view.imagePaint)
    }.onFailure { e ->
        appCtx.toastOnUi(e.localizedMessage)
    }
}

private fun ImageDrawCache.updateDrawCache(
    start: Float,
    bitmap: Bitmap,
    containerW: Float,
    containerH: Float
) {
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

private fun ReviewColumn.drawReviewColumn(canvas: Canvas) {
    if (count == 0) return
    // 容器 = column [start, end] × textLine.height（与 ImageColumn 同款容器）
    val containerW = end - start
    val containerH = textLine.height
    val scale = minOf(containerW / ReviewIcon.ICON_W, containerH / ReviewIcon.ICON_H) * ReviewIcon.FILL_RATIO
    val iconW = ReviewIcon.ICON_W * scale
    val iconH = ReviewIcon.ICON_H * scale
    val left = start + (containerW - iconW) / 2f
    val top = (containerH - iconH) / 2f

    val paint = ChapterProvider.reviewPaint
    // 外圈复用缓存 bitmap（ALPHA_8 + drawBitmap 传 paint 上色，省 3/4 内存）
    // bitmap 含 padding 防 stroke 被边界裁；绘制时反向偏移 padding
    val pad = kotlin.math.ceil(scale).toInt()
    val bmp = ReviewIcon.getOutlineBitmap(iconW, iconH, scale, pad)
    canvas.drawBitmap(bmp, left - pad, top - pad, paint)

    // 数字字号同章稳定，textSize 仅在 scale 变化时切；fm 也缓存
    ReviewIcon.ensureCountTextSize(paint, scale)
    canvas.drawText(
        countText,
        left + 12.5f * scale,
        top + 10f * scale - ReviewIcon.cachedFmCenter,
        paint
    )
}

/**
 * 段评气泡外圈 bitmap 缓存与字号缓存（渲染侧共享）。
 * 形状参照设计稿：胶囊气泡 + 左下小尾巴 + 居中数字。
 * 外圈（气泡 + 尾巴）按 size+color 缓存到 ALPHA_8 bitmap，全章复用，每帧只重画数字。
 */
private object ReviewIcon {
    const val ICON_W = 25f
    const val ICON_H = 29f
    const val FILL_RATIO = 0.95f

    // 全章/全应用共享一份外圈 bitmap：同字号同 size 下，所有气泡复用
    // size 变化（字号、布局）→ 重新渲染并替换缓存
    @Volatile
    private var cachedBitmap: Bitmap? = null
    private var cachedW: Int = 0
    private var cachedH: Int = 0

    // 数字 textSize 缓存：scale 同章一致，多列共用同一份
    private var cachedScale: Float = Float.NaN
    var cachedFmCenter: Float = 0f
        private set

    fun ensureCountTextSize(paint: Paint, scale: Float) {
        if (scale == cachedScale) return
        paint.textSize = 12f * scale
        val fm = paint.fontMetrics
        cachedFmCenter = (fm.ascent + fm.descent) / 2f
        cachedScale = scale
    }

    @Synchronized
    fun getOutlineBitmap(iconW: Float, iconH: Float, scale: Float, pad: Int): Bitmap {
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
