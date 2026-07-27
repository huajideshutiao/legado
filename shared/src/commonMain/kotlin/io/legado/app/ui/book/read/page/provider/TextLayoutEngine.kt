package io.legado.app.ui.book.read.page.provider

import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import kotlin.math.roundToInt

/**
 * 排版引擎纯算术面（已下沉 shared commonMain）。
 *
 * 把 TextChapterLayout 中 justify 族/exceed/分页判定等 8 个纯算术方法下沉到本类，
 * 依赖的平台行为（列构造、协程取消检查、页面完成回调）通过 [ColumnFactory] /
 * [TextLayoutCallback] 接口注入，由 app 端 TextChapterLayout 实现。
 *
 * 引擎持有排版过程状态（[durY] / [absStartX] / [pendingTextPage] / [stringBuilder]），
 * TextChapterLayout 通过这些字段读写状态，避免重复维护。
 *
 * 不下沉的 android 依赖：
 * - ChapterProvider.reviewChar/srcReplaceChar 常量（通过 ColumnFactory 实现封装）
 * - reviewCountMap 段评计数（通过 ColumnFactory 实现封装）
 * - currentCoroutineContext().ensureActive()（通过 TextLayoutCallback.ensureActive 注入）
 * - onPageCompleted 平台回调（通过 TextLayoutCallback.onPageCompleted 注入）
 */
class TextLayoutEngine(
    val visibleWidth: Int,
    val visibleHeight: Int,
    val viewWidth: Int,
    val paddingLeft: Int,
    val doublePage: Boolean,
    private val textFullJustify: Boolean,
    private val columnFactory: ColumnFactory,
    private val callback: TextLayoutCallback,
) {

    var durY: Float = 0f
    var absStartX: Int = paddingLeft
    var pendingTextPage: TextPage = TextPage()
    val stringBuilder: StringBuilder = StringBuilder()

    /**
     * justify 入口：根据 textFullJustify 与空格数选择 justify 策略。
     * 原实现：TextChapterLayout.addCharsToLineMiddle (TCL:738-791)。
     */
    suspend fun addCharsToLineMiddle(
        textLine: TextLine,
        words: List<String>,
        measurer: TextMeasurer,
        desiredWidth: Float,
        startX: Float,
        textWidths: List<Float>,
        imgList: MutableList<ImgData>?
    ) {
        if (!textFullJustify) {
            addCharsToLineNatural(
                textLine,
                words,
                startX,
                textWidths,
                imgList
            ); return
        }
        textLine.startX = absStartX + startX
        val residualWidth = visibleWidth - startX - desiredWidth
        val spaceSize = words.count { it == " " }
        if (spaceSize > 0) justifyBySpaces(
            textLine,
            words,
            startX,
            textWidths,
            residualWidth,
            spaceSize,
            imgList
        )
        else justifyByLetterSpacing(
            textLine,
            words,
            startX,
            textWidths,
            residualWidth,
            measurer,
            imgList
        )
        exceed(absStartX, textLine, words)
    }

    /**
     * 按空格均分剩余宽度。原实现：TextChapterLayout.justifyBySpaces (TCL:788-813)。
     */
    suspend fun justifyBySpaces(
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        textWidths: List<Float>,
        residualWidth: Float,
        spaceSize: Int,
        imgList: MutableList<ImgData>?
    ) {
        val d = residualWidth / spaceSize; textLine.wordSpacing = d;
        var x = startX
        for (index in words.indices) {
            val char = words[index];
            val cw = textWidths[index]
            val x1 = if (char == " " && index != words.lastIndex) x + cw + d else x + cw
            addCharToLine(absStartX, textLine, char, x, x1, index + 1 == words.size, imgList)
            x = x1
        }
    }

    /**
     * 按字间距均分剩余宽度。原实现：TextChapterLayout.justifyByLetterSpacing (TCL:810-838)。
     */
    suspend fun justifyByLetterSpacing(
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        textWidths: List<Float>,
        residualWidth: Float,
        measurer: TextMeasurer,
        imgList: MutableList<ImgData>?
    ) {
        val gapCount = words.lastIndex;
        val d = if (gapCount > 0) residualWidth / gapCount else 0f
        textLine.extraLetterSpacingOffsetX = -d / 2; textLine.extraLetterSpacing =
            d / measurer.textSizePx
        var x = startX
        for (index in words.indices) {
            val char = words[index];
            val cw = textWidths[index]
            val x1 = if (index != words.lastIndex) x + cw + d else x + cw
            addCharToLine(absStartX, textLine, char, x, x1, index + 1 == words.size, imgList)
            x = x1
        }
    }

    /**
     * 自然行布局（无 justify）。原实现：TextChapterLayout.addCharsToLineNatural (TCL:835-859)。
     */
    suspend fun addCharsToLineNatural(
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        textWidths: List<Float>,
        imgList: MutableList<ImgData>?
    ) {
        textLine.startX = absStartX + startX;
        var x = startX
        for (index in words.indices) {
            val char = words[index];
            val cw = textWidths[index];
            val x1 = x + cw
            addCharToLine(absStartX, textLine, char, x, x1, index + 1 == words.size, imgList)
            x = x1
        }
        exceed(absStartX, textLine, words)
    }

    /**
     * 单字入列。原实现：TextChapterLayout.addCharToLine (TCL:856-873)。
     * 列构造委托给 [columnFactory]（平台依赖：reviewChar/srcReplaceChar 常量、段评计数、imgList）。
     */
    suspend fun addCharToLine(
        absStartX: Int,
        textLine: TextLine,
        char: String,
        xStart: Float,
        xEnd: Float,
        isLineEnd: Boolean,
        imgList: MutableList<ImgData>?
    ) {
        val column = columnFactory.createColumn(absStartX, char, xStart, xEnd, imgList)
        textLine.addColumn(column)
    }

    /**
     * 越界回压：行末超出可视区时反向压缩列坐标。原实现：TextChapterLayout.exceed (TCL:890-912)。
     */
    fun exceed(absStartX: Int, textLine: TextLine, words: List<String>) {
        var size = words.size; if (size < 2) return
        val visibleEnd = absStartX + visibleWidth;
        val columns = textLine.columns;
        var offset = 0
        val endColumn = if (words.last() == " ") {
            size--; offset++; columns[columns.lastIndex - 1]
        } else columns.last()
        val endX = endColumn.end.roundToInt()
        if (endX > visibleEnd) {
            textLine.exceed = true;
            val cc = (endX - visibleEnd) / size
            for (i in 0..<size) {
                textLine.getColumnReverseAt(i, offset)
                    .let { val py = cc * (size - i); it.start -= py; it.end -= py }
            }
        }
    }

    /**
     * 分页判定：超过可视高或显式触发时收尾当前页、开新页。
     * 原实现：TextChapterLayout.prepareNextPageIfNeed (TCL:909-929)。
     * 平台回调（ensureActive/onPageCompleted）通过 [callback] 注入。
     */
    suspend fun prepareNextPageIfNeed(requestHeight: Float = -1f) {
        if (requestHeight > visibleHeight || requestHeight == -1f) {
            if (pendingTextPage.height < durY) pendingTextPage.height = durY
            if (doublePage && absStartX < viewWidth / 2) {
                pendingTextPage.leftLineSize = pendingTextPage.lineSize
                absStartX = viewWidth / 2 + paddingLeft
            } else {
                if (pendingTextPage.leftLineSize == 0) pendingTextPage.leftLineSize =
                    pendingTextPage.lineSize
                pendingTextPage.text = stringBuilder.toString()
                callback.ensureActive(); callback.onPageCompleted()
                pendingTextPage = TextPage(); stringBuilder.clear(); absStartX = paddingLeft
            }
            durY = 0f
        }
    }
}
