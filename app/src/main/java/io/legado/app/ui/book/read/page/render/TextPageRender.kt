package io.legado.app.ui.book.read.page.render

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import androidx.core.graphics.withTranslation
import io.legado.app.help.PaintPool
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.canvasrecorder.CanvasRecorder
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.canvasrecorder.recordIfNeeded
import io.legado.app.utils.dpToPx

/**
 * TextPage 渲染侧（android 绘制/测量行为）。
 * 数据模型 TextPage 保持纯 Kotlin，绘制与消息页 StaticLayout 排版集中于此。
 */

/**
 * 计算文字位置,只用作单页面内容
 */
@Suppress("DEPRECATION")
fun TextPage.format(): TextPage {
    if (lines.isEmpty()) isMsgPage = true
    if (isMsgPage && ChapterProvider.viewWidth > 0) {
        clearTextLines()
        val visibleWidth = ChapterProvider.visibleRight - ChapterProvider.paddingLeft
        val paint = ChapterProvider.contentPaint
        val layout = StaticLayout(
            text, paint, visibleWidth,
            Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false
        )
        val letterSpacing = paint.letterSpacing * paint.textSize
        var y = (ChapterProvider.visibleHeight - layout.height) / 2f
        if (y < 0) y = 0f
        for (lineIndex in 0 until layout.lineCount) {
            val textLine = TextLine()
            textLine.lineTop = ChapterProvider.paddingTop + y + layout.getLineTop(lineIndex)
            textLine.lineBase =
                ChapterProvider.paddingTop + y + layout.getLineBaseline(lineIndex)
            textLine.lineBottom =
                ChapterProvider.paddingTop + y + layout.getLineBottom(lineIndex)
            var x = ChapterProvider.paddingLeft +
                    (visibleWidth - layout.getLineMax(lineIndex)) / 2
            textLine.text =
                text.substring(layout.getLineStart(lineIndex), layout.getLineEnd(lineIndex))
            for (i in textLine.text.indices) {
                val char = textLine.text[i].toString()
                var cw = StaticLayout.getDesiredWidth(char, paint)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    cw += letterSpacing
                }
                val x1 = x + cw
                textLine.addColumn(
                    TextColumn(start = x, end = x1, char)
                )
                x = x1
            }
            addLine(textLine)
        }
        height = ChapterProvider.visibleHeight.toFloat()
        upRenderHeight()
        invalidate()
        isCompleted = true
    }
    return this
}

fun TextPage.draw(view: ContentTextView, canvas: Canvas, relativeOffset: Float) {
    if (AppConfig.optimizeRender) {
        render(view)
        canvas.withTranslation(0f, relativeOffset) {
            ensureRecorder().draw(this)
        }
    } else {
        canvas.withTranslation(0f, relativeOffset) {
            drawPage(view, this)
        }
    }
}

@Suppress("unused")
private fun TextPage.drawDebugInfo(canvas: Canvas) {
    ChapterProvider.run {
        val paint = PaintPool.obtain()
        paint.style = Paint.Style.STROKE
        canvas.drawRect(
            paddingLeft.toFloat(),
            0f,
            (paddingLeft + visibleWidth).toFloat(),
            height - 1.dpToPx(),
            paint
        )
        PaintPool.recycle(paint)
    }
}

private fun TextPage.drawPage(view: ContentTextView, canvas: Canvas) {
    for (i in lines.indices) {
        val line = lines[i]
        canvas.withTranslation(0f, line.lineTop) {
            line.draw(view, this)
        }
    }
}

fun TextPage.render(view: ContentTextView): Boolean {
    if (!isCompleted) return false
    return ensureRecorder().recordIfNeeded(view.width, renderHeight) {
        drawPage(view, this)
    }
}

/**
 * lazy 创建并注入 CanvasRecorder 到 TextPage.canvasRecorder 句柄。
 *
 * 数据层 TextPage 持有 CanvasRecorderHandle 接口（可为 null），
 * render 侧在首次 draw/render 时创建具体 CanvasRecorder 并包装成 Handle 注入。
 * 后续 TextPage.invalidate()/recycleRecorders() 通过接口回调到本实现。
 */
fun TextPage.ensureRecorder(): CanvasRecorder {
    val existing = canvasRecorder
    if (existing is CanvasRecorderHandleImpl) {
        return existing.recorder
    }
    val recorder = CanvasRecorderFactory.create(true)
    canvasRecorder = CanvasRecorderHandleImpl(recorder)
    return recorder
}
