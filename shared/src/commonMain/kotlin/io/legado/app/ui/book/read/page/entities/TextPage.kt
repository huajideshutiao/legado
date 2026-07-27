package io.legado.app.ui.book.read.page.entities

import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.utils.formatPercentUs
import kotlin.jvm.JvmField
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 页面信息
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
data class TextPage(
    var index: Int = 0,
    var text: String = "",
    var title: String = "",
    private val textLines: ArrayList<TextLine> = arrayListOf(),
    var chapterSize: Int = 0,
    var chapterIndex: Int = 0,
    var height: Float = 0f,
    var leftLineSize: Int = 0,
    var renderHeight: Int = 0
) {

    companion object {
        val emptyTextPage = TextPage()
    }

    val lines: List<TextLine> get() = textLines
    val lineSize: Int get() = textLines.size
    val charSize: Int get() = text.length.coerceAtLeast(1)
    val chapterPosition: Int get() = textLines.first().chapterPosition
    val searchResult = hashSetOf<TextColumn>()
    var isMsgPage: Boolean = false

    /**
     * render 侧 Canvas 录制缓存句柄。
     * 由 render 侧 lazy 注入（见 TextPageRender.ensureRecorder），
     * 数据层只通过接口触发 invalidate/recycle，不直接持有 android CanvasRecorder。
     */
    var canvasRecorder: CanvasRecorderHandle? = null

    var doublePage = false

    /**
     * 排版几何参数，由排版层（TextChapterLayout）在 onPageCompleted 时注入。
     * 数据化前由 ChapterProvider / ReadBookConfig 静态字段提供，
     * 现改为实例字段以解耦 android 依赖，供 upLinesPosition / paddingTop 使用。
     */
    var paddingTop: Int = 0
    var textBottomJustify: Boolean = false
    var visibleHeight: Int = 0
    var visibleBottom: Int = 0
    var contentPaintTextHeight: Float = 0f
    var lineSpacingExtra: Float = 0f

    var isCompleted = false
    var hasReadAloudSpan = false

    /**
     * 所属章节引用。类型为 [TextChapterRef]（commonMain 最小接口，仅暴露 pageSize），
     * 实际运行时由 app 端 TextChapter 实现并注入。
     * 数据化前字段类型为 TextChapter（依赖 Book/BookChapter 等 android 类，无法下沉 commonMain）。
     * 默认 null，调用方通过 [TextPage.getTextChapter] 扩展函数（app 端）取回强类型 TextChapter。
     */
    @JvmField
    var textChapter: TextChapterRef? = null
    val pageSize get() = textChapter?.pageSize ?: 0

    val paragraphs by lazy {
        paragraphsInternal
    }

    val paragraphsInternal: ArrayList<TextParagraph>
        get() {
            val paragraphs = arrayListOf<TextParagraph>()
            val lines = textLines.filter { it.paragraphNum > 0 }
            val offset = lines.first().paragraphNum - 1
            lines.forEach { line ->
                if (paragraphs.lastIndex < line.paragraphNum - offset - 1) {
                    paragraphs.add(TextParagraph(0))
                }
                paragraphs[line.paragraphNum - offset - 1].textLines.add(line)
            }
            return paragraphs
        }

    fun addLine(line: TextLine) {
        line.textPage = this
        textLines.add(line)
    }

    fun getLine(index: Int): TextLine {
        return textLines.getOrElse(index) {
            textLines.last()
        }
    }

    /**
     * 清空行，仅供渲染侧 format() 重排消息页使用
     */
    fun clearTextLines() {
        textLines.clear()
    }

    /**
     * 底部对齐更新行位置
     *
     * 几何参数（visibleHeight/visibleBottom/contentPaintTextHeight/lineSpacingExtra/textBottomJustify）
     * 由排版层在构造时注入为字段，本方法不再直接引用 ChapterProvider / ReadBookConfig。
     */
    fun upLinesPosition() {
        if (!textBottomJustify) return
        if (textLines.size <= 1) return
        if (leftLineSize == 0) {
            leftLineSize = lineSize
        }
        run {
            val lastLine = textLines[leftLineSize - 1]
            if (lastLine.isImage) return@run
            val lastLineHeight = with(lastLine) { lineBottom - lineTop }
            val pageHeight = lastLine.lineBottom + contentPaintTextHeight * lineSpacingExtra
            if (visibleHeight - pageHeight >= lastLineHeight) return@run
            val surplus = (visibleBottom - lastLine.lineBottom)
            if (surplus == 0f) return@run
            height += surplus
            val tj = surplus / (leftLineSize - 1)
            for (i in 1 until leftLineSize) {
                val line = textLines[i]
                line.lineTop += tj * i
                line.lineBase += tj * i
                line.lineBottom += tj * i
            }
        }
        if (leftLineSize == lineSize) return
        run {
            val lastLine = textLines.last()
            if (lastLine.isImage) return@run
            val lastLineHeight = with(lastLine) { lineBottom - lineTop }
            val pageHeight = lastLine.lineBottom + contentPaintTextHeight * lineSpacingExtra
            if (visibleHeight - pageHeight >= lastLineHeight) return@run
            val surplus = (visibleBottom - lastLine.lineBottom)
            if (surplus == 0f) return@run
            val tj = surplus / (textLines.size - leftLineSize - 1)
            for (i in leftLineSize + 1 until textLines.size) {
                val line = textLines[i]
                val surplusIndex = i - leftLineSize
                line.lineTop += tj * surplusIndex
                line.lineBase += tj * surplusIndex
                line.lineBottom += tj * surplusIndex
            }
        }
    }

    /**
     * 移除朗读标志
     *
     * isReadAloud setter 副作用剥离后，由本方法显式触发 invalidateAll 重绘。
     */
    fun removePageAloudSpan(): TextPage {
        if (!hasReadAloudSpan) {
            return this
        }
        hasReadAloudSpan = false
        for (i in textLines.indices) {
            textLines[i].isReadAloud = false
        }
        invalidateAll()
        return this
    }

    /**
     * 更新朗读标志
     * @param aloudSpanStart 朗读文字开始位置
     *
     * isReadAloud setter 副作用剥离后，由本方法显式同步 hasReadAloudSpan 与 invalidateAll。
     */
    fun upPageAloudSpan(aloudSpanStart: Int) {
        removePageAloudSpan()
        var lineStart = 0
        for (index in textLines.indices) {
            val textLine = textLines[index]
            val lineLength = textLine.text.length + if (textLine.isParagraphEnd) 1 else 0
            if (aloudSpanStart >= lineStart && aloudSpanStart < lineStart + lineLength) {
                for (i in index - 1 downTo 0) {
                    if (textLines[i].isParagraphEnd) {
                        break
                    } else {
                        textLines[i].isReadAloud = true
                    }
                }
                for (i in index until textLines.size) {
                    if (textLines[i].isParagraphEnd) {
                        textLines[i].isReadAloud = true
                        break
                    } else {
                        textLines[i].isReadAloud = true
                    }
                }
                hasReadAloudSpan = true
                invalidateAll()
                break
            }
            lineStart += lineLength
        }
    }

    /**
     * 阅读进度
     *
     * 原实现使用 DecimalFormat("0.0%")，依赖 java.text；
     * 后改为 String.format(Locale.US, ...) 保持原格式（US locale 小数点为 "."），
     * 消除 java.text 依赖，便于后续 KMP 下沉。
     *
     * KMP 化: 进一步抽出 [formatPercentUs] expect/actual,
     * commonMain 仅声明, jvm/android/ios/ohos 各自实现, 消除 java.util.Locale 阻塞 iOS 编译。
     */
    val readProgress: String
        get() {
            if (chapterSize == 0 || pageSize == 0 && chapterIndex == 0) {
                return "0.0%"
            } else if (pageSize == 0) {
                return formatPercent((chapterIndex + 1.0f) / chapterSize.toDouble())
            }
            var percent = formatPercent(
                chapterIndex * 1.0f / chapterSize + 1.0f / chapterSize * (index + 1) / pageSize.toDouble()
            )
            if (percent == "100.0%" && (chapterIndex + 1 != chapterSize || index + 1 != pageSize)) {
                percent = "99.9%"
            }
            return percent
        }

    /**
     * 百分比格式化 (value 为 0~1 区间), 委托 KMP expect [formatPercentUs]。
     *
     * - jvm/android: 仍走 `String.format(Locale.US, "%.1f%%", value * 100)`, 与原实现完全一致;
     * - iOS/鸿蒙: 纯 Kotlin roundToInt 实现, 不依赖 java.util.Locale。
     */
    private fun formatPercent(value: Double): String =
        formatPercentUs(value)

    /**
     * 根据行和列返回字符在本页的位置
     * @param lineIndex 字符在第几行
     * @param columnIndex 字符在第几列
     * @return 字符在本页位置
     */
    fun getPosByLineColumn(lineIndex: Int, columnIndex: Int): Int {
        var length = 0
        val maxIndex = min(lineIndex, lineSize - 1)
        for (index in 0 until maxIndex) {
            length += textLines[index].charSize
            if (textLines[index].isParagraphEnd) {
                length++
            }
        }
        val columns = textLines[maxIndex].columns
        for (index in 0 until columnIndex) {
            val column = columns[index]
            if (column is TextColumn) {
                length += column.charData.length
            }
        }
        return length
    }

    /**
     * 判断章节字符位置是否在这一页中
     *
     * @param chapterPos 章节字符位置
     * @return
     */
    fun containPos(chapterPos: Int): Boolean {
        val line = lines.first()
        val startPos = line.chapterPosition
        val endPos = startPos + charSize
        return chapterPos in startPos..<endPos
    }

    fun invalidate() {
        canvasRecorder?.invalidate()
    }

    fun invalidateAll() {
        for (i in lines.indices) {
            lines[i].invalidateSelf()
        }
        invalidate()
    }

    fun recycleRecorders() {
        canvasRecorder?.recycle()
        for (i in lines.indices) {
            lines[i].recycleRecorder()
        }
    }

    fun hasImageOrEmpty(): Boolean {
        return textLines.any { it.isImage } || textLines.isEmpty()
    }

    fun upRenderHeight() {
        renderHeight = ceil(lines.last().lineBottom).toInt()
        if (leftLineSize > 0 && leftLineSize != lines.size) {
            val leftHeight = ceil(lines[leftLineSize - 1].lineBottom).toInt()
            renderHeight = max(renderHeight, leftHeight)
        }
    }
}
