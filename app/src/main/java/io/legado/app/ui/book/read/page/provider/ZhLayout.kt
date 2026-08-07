package io.legado.app.ui.book.read.page.provider

import android.graphics.Paint
import android.text.Layout
import android.text.TextPaint
import java.util.WeakHashMap

/**
 * 针对中文的断行排版处理-by hoodie13
 * 因为StaticLayout对标点处理不符合国人习惯，继承Layout
 *
 * 断行状态机已抽到 shared commonMain 的纯 Kotlin [ZhLineBreaker]（JVM 可测）；
 * 本类仅保留 android.text.Layout 外壳（复用 getLineStart 等接口）+ 平台测量收口（经 [TextMeasurer]）。
 * */
@Suppress("MemberVisibilityCanBePrivate", "unused")
class ZhLayout(
    text: CharSequence,
    textPaint: TextPaint,
    width: Int,
    words: List<String>,
    widths: List<Float>,
    indentSize: Int,
    measurer: TextMeasurer,
) : Layout(text, textPaint, width, Alignment.ALIGN_NORMAL, 0f, 0f) {
    companion object {
        private val cnCharWidthCache = WeakHashMap<Paint, Float>()
    }

    private val cnCharWidth = cnCharWidthCache[textPaint]
        ?: measurer.measureWidth("我").also {
            cnCharWidthCache[textPaint] = it
        }

    private val breaker = ZhLineBreaker(
        words, widths, indentSize, width, cnCharWidth, measurer.letterSpacingPx
    )

    override fun getLineCount(): Int {
        return breaker.lineCount
    }

    override fun getLineTop(line: Int): Int {
        return 0
    }

    override fun getLineDescent(line: Int): Int {
        return 0
    }

    override fun getLineStart(line: Int): Int {
        return breaker.lineStart[line]
    }

    fun getLineStartCluster(line: Int): Int {
        return breaker.lineStartCluster[line]
    }

    fun getLineEndCluster(line: Int): Int {
        return breaker.lineStartCluster[line + 1]
    }

    override fun getParagraphDirection(line: Int): Int {
        return 0
    }

    override fun getLineContainsTab(line: Int): Boolean {
        return true
    }

    override fun getLineDirections(line: Int): Directions? {
        return null
    }

    override fun getTopPadding(): Int {
        return 0
    }

    override fun getBottomPadding(): Int {
        return 0
    }

    override fun getLineWidth(line: Int): Float {
        return breaker.lineWidth[line]
    }

    override fun getEllipsisStart(line: Int): Int {
        return 0
    }

    override fun getEllipsisCount(line: Int): Int {
        return 0
    }

}
