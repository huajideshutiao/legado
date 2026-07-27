package io.legado.app.ui.book.read.page.render

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.os.Build
import io.legado.app.help.PaintPool
import io.legado.app.help.book.isImage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.CanvasRecorderHandle
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.canvasrecorder.CanvasRecorder
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.canvasrecorder.recordIfNeededThenDraw
import io.legado.app.utils.dpToPx

/**
 * TextLine 渲染侧（android 绘制/测量行为）。
 * 数据模型 TextLine 保持纯 Kotlin，绘制与 FontMetrics 相关逻辑集中于此。
 */

private val atLeastApi35 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

private val wordSpacingWorking by lazy {
    // issue 3785 3846
    val paint = PaintPool.obtain()
    val text = "一二 三"
    val width1 = paint.measureText(text)
    try {
        paint.wordSpacing = 10f
        val width2 = paint.measureText(text)
        width2 - width1 == 10f
    } catch (e: NoSuchMethodError) {
        false
    } finally {
        PaintPool.recycle(paint)
    }
}

fun TextLine.upTopBottom(durY: Float, textHeight: Float, descent: Float) {
    lineTop = ChapterProvider.paddingTop + durY
    lineBottom = lineTop + textHeight
    lineBase = lineBottom - descent
}

fun TextLine.draw(view: ContentTextView, canvas: Canvas) {
    if (AppConfig.optimizeRender && !isImage) {
        ensureRecorder().recordIfNeededThenDraw(canvas, view.width, height.toInt()) {
            drawTextLine(view, this)
        }
    } else {
        drawTextLine(view, canvas)
    }
}

/**
 * lazy 创建并注入 CanvasRecorder 到 TextLine.canvasRecorder 句柄。
 *
 * 数据层 TextLine 持有 CanvasRecorderHandle 接口（可为 null），
 * render 侧在首次 draw 时创建具体 CanvasRecorder 并包装成 Handle 注入。
 * 后续 TextLine.invalidate()/recycleRecorder() 通过接口回调到本实现。
 */
fun TextLine.ensureRecorder(): CanvasRecorder {
    val existing = canvasRecorder
    if (existing is CanvasRecorderHandleImpl) {
        return existing.recorder
    }
    val recorder = CanvasRecorderFactory.create()
    canvasRecorder = CanvasRecorderHandleImpl(recorder)
    return recorder
}

/**
 * CanvasRecorderHandle 的 render 侧实现，包装 CanvasRecorder。
 * internal 供同包 TextPageRender 复用。
 */
internal class CanvasRecorderHandleImpl(val recorder: CanvasRecorder) : CanvasRecorderHandle {
    override fun invalidate() = recorder.invalidate()
    override fun recycle() = recorder.recycle()
}

private fun TextLine.drawTextLine(view: ContentTextView, canvas: Canvas) {
    if (checkFastDraw()) {
        fastDrawTextLine(view, canvas)
    } else {
        for (i in columns.indices) {
            columns[i].draw(view, canvas)
        }
    }

    // 墨水屏模式下的朗读和搜索下划线
    if (AppConfig.isEInkMode && (isReadAloud || searchResultColumnCount > 0)) {
        val underlinePaint = PaintPool.obtain()
        underlinePaint.set(ChapterProvider.contentPaint)
        underlinePaint.strokeWidth = 1.dpToPx().toFloat()
        val lineY = height - 1.dpToPx()
        canvas.drawLine(lineStart + indentWidth, lineY, lineEnd, lineY, underlinePaint)
        PaintPool.recycle(underlinePaint)
    }

    if (ReadBookConfig.underline && !isImage && ReadBook.book?.isImage != true) {
        drawUnderline(canvas)
    }
}

@SuppressLint("NewApi")
private fun TextLine.fastDrawTextLine(view: ContentTextView, canvas: Canvas) {
    val textPaint = if (isTitle) {
        ChapterProvider.titlePaint
    } else {
        ChapterProvider.contentPaint
    }
    val textColor = if (isReadAloud) {
        ThemeStore.accentColor
    } else {
        ReadBookConfig.textColor
    }
    if (textPaint.color != textColor) {
        textPaint.color = textColor
    }
    val paint = PaintPool.obtain()
    paint.set(textPaint)
    val letterSpacing = paint.letterSpacing * paint.textSize
    val letterSpacingHalf = letterSpacing * 0.5f
    if (extraLetterSpacing != 0f) {
        paint.letterSpacing += extraLetterSpacing
    }
    if (wordSpacing != 0f) {
        paint.wordSpacing = wordSpacing
    }
    val offsetX = if (atLeastApi35) letterSpacingHalf else extraLetterSpacingOffsetX
    canvas.drawText(text, indentSize, text.length, startX + offsetX, lineBase - lineTop, paint)
    PaintPool.recycle(paint)
    for (i in columns.indices) {
        val column = columns[i] as TextColumn
        if (column.selected) {
            canvas.drawRect(column.start, 0f, column.end, height, view.selectedPaint)
        }
    }
}

/**
 * 绘制下划线
 */
private fun TextLine.drawUnderline(canvas: Canvas) {
    val lineY = height - 1.dpToPx()
    canvas.drawLine(
        lineStart + indentWidth,
        lineY,
        lineEnd,
        lineY,
        ChapterProvider.contentPaint
    )
}

fun TextLine.checkFastDraw(): Boolean {
    if (!AppConfig.optimizeRender || exceed || !onlyTextColumn || textPage.isMsgPage) {
        return false
    }
    if (wordSpacing != 0f && (!wordSpacingWorking)) {
        return false
    }
    return searchResultColumnCount == 0
}
