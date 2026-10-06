package io.legado.app.ui.book.read.page.overlay

import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import androidx.compose.ui.geometry.Rect

/**
 * 搜索命中范围（不可变外部状态，使用章节正文的半开字符区间）。
 *
 * @param chapterIndex 章节序号
 * @param start 章内起始字符偏移（含）
 * @param endExclusive 章内结束字符偏移（不含）
 */
data class SearchHighlightOverlay(
    val chapterIndex: Int,
    val start: Int,
    val endExclusive: Int,
)

/**
 * 高亮线型 (上色由 color 可空独立表达, 线型互斥): 0 无线, 1 下划线, 2 波浪线, 3 删除线。
 */
object HighlightLineStyle {
    const val NONE = 0
    const val UNDERLINE = 1
    const val WAVY = 2
    const val STRIKETHROUGH = 3
}

/**
 * 静态章内区间高亮 (批注回显 / 关键词命中共用的 overlay 状态, 不可变)。
 *
 * 与 [SearchHighlightOverlay] 同一字符账本 (每个 TextColumn 消耗 charData.length,
 * 非文字列消耗 1), 章内偏移口径同 `TextLine.chapterPosition`。
 *
 * @param chapterIndex 章节序号
 * @param start 章内起始字符偏移 (含)
 * @param endExclusive 章内结束字符偏移 (不含)
 * @param color 上色颜色 (ARGB, 0x50 半透明色块); null = 不上色 (默认), 此时线取主题 accent 色
 * @param lineStyle 线型 (见 [HighlightLineStyle]), 默认下划线
 * @param underlineId 来源批注 Bookmark.time (点击命中后反查实体用); 关键词命中恒 0
 */
data class HighlightOverlay(
    val chapterIndex: Int,
    val start: Int,
    val endExclusive: Int,
    val color: Int? = null,
    val lineStyle: Int = HighlightLineStyle.UNDERLINE,
    val underlineId: Long = 0,
)

/**
 * TTS 朗读高亮位置（不可变状态，由 ViewModel 持有）。
 *
 * @param chapterIndex 章节序号：与页的 [TextPage.chapterIndex] 相等才投影，
 *   否则相邻章的页会命中同一段章内区间
 * @param chapterPos 朗读游标的章内字符位置（口径同 `TextLine.chapterPosition`）
 */
data class TTSHighlightOverlay(
    val chapterIndex: Int,
    val chapterPos: Int,
)

/**
 * 页面叠加层几何空间投影器（纯函数无副作用）。
 *
 * 负责把抽象交互状态（选区、TTS 朗读高亮、搜索命中）投影为页内几何，
 * 彻底解耦排版数据（不可变快照）与渲染叠加层——排版产物不再持有任何交互标志位。
 *
 * 全部投影都是 emit 内联/轻量返回值形态：绘制期边投影边画，不产生矩形集合分配（拖拽热路径）。
 * 页内字符偏移一律取排版期写入的 `TextLine.pagePosition`（= `TextPage.text` 的索引口径）。
 */
object PageOverlayProjector {

    /**
     * 选区投影算法（行列索引维度）：起止行列之间逐行投影为一个矩形，起止顺序自动归正。
     *
     * @param startLineIndex 起始行索引（0-based）
     * @param startColIndex 起始列索引（0-based）
     * @param endLineIndex 结束行索引（0-based）
     * @param endColIndex 结束列索引（0-based，含）
     */
    inline fun projectSelection(
        textPage: TextPage,
        startLineIndex: Int,
        startColIndex: Int,
        endLineIndex: Int,
        endColIndex: Int,
        emit: (left: Float, top: Float, right: Float, bottom: Float, lineIndex: Int) -> Unit,
    ) {
        val lines = textPage.lines
        if (lines.isEmpty()) return

        val isForward = startLineIndex < endLineIndex ||
            (startLineIndex == endLineIndex && startColIndex <= endColIndex)
        val sLine = if (isForward) startLineIndex else endLineIndex
        val sCol = if (isForward) startColIndex else endColIndex
        val eLine = if (isForward) endLineIndex else startLineIndex
        val eCol = if (isForward) endColIndex else startColIndex

        for (lineIndex in sLine.coerceAtLeast(0)..eLine.coerceAtMost(lines.lastIndex)) {
            val line = lines[lineIndex]
            val columns = line.columns
            if (columns.isEmpty()) continue

            val colStart = if (lineIndex == sLine) sCol.coerceIn(0, columns.lastIndex) else 0
            val colEnd = if (lineIndex == eLine) eCol.coerceIn(0, columns.lastIndex) else columns.lastIndex

            if (colStart <= colEnd) {
                emit(
                    columns[colStart].start,
                    line.lineTop,
                    columns[colEnd].end,
                    line.lineBottom,
                    lineIndex,
                )
            }
        }
    }

    /**
     * TTS 朗读高亮投影（段落维度）：把 [highlight] 的章内位置折算成页内偏移，
     * 定位所在行后向前/向后扩到段落边界，返回高亮行的索引闭区间；
     * 不落在本页（或不是本章）时返回 [IntRange.EMPTY]。
     *
     * 页内偏移折算：同页内 `chapterPosition - pagePosition` 恒为定值
     * （排版期 `calcChapterPosition` 的 base，等于 `TextChapterShared.getReadLength(pageIndex)`）。
     * 行覆盖区间取 `[pagePosition, 下一行 pagePosition)`——段末换行、图片占位字符都含在差值里。
     */
    fun projectTTS(textPage: TextPage, highlight: TTSHighlightOverlay): IntRange {
        if (textPage.chapterIndex != highlight.chapterIndex) return IntRange.EMPTY
        val textLines = textPage.lines
        val firstLine = textLines.firstOrNull() ?: return IntRange.EMPTY
        val pagePos = highlight.chapterPos - (firstLine.chapterPosition - firstLine.pagePosition)
        if (pagePos < 0) return IntRange.EMPTY

        var targetIndex = -1
        for (index in textLines.indices) {
            val textLine = textLines[index]
            val lineEnd = textLines.getOrNull(index + 1)?.pagePosition
                ?: (textLine.pagePosition + textLine.charSize + if (textLine.isParagraphEnd) 1 else 0)
            if (pagePos < lineEnd) {
                targetIndex = index
                break
            }
        }
        if (targetIndex < 0) return IntRange.EMPTY

        var startIndex = targetIndex
        for (i in targetIndex - 1 downTo 0) {
            if (textLines[i].isParagraphEnd) break
            startIndex = i
        }
        var endIndex = targetIndex
        for (i in targetIndex until textLines.size) {
            endIndex = i
            if (textLines[i].isParagraphEnd) break
        }
        return startIndex..endIndex
    }

    /**
     * 判断章内半开区间是否与搜索命中相交；供文字色、E-Ink 下划线与几何投影
     * 共用同一套章节门控及边界口径。
     */
    fun isSearchRangeHit(
        textPage: TextPage,
        highlight: SearchHighlightOverlay,
        start: Int,
        endExclusive: Int,
    ): Boolean = textPage.chapterIndex == highlight.chapterIndex &&
        start < endExclusive && start < highlight.endExclusive && endExclusive > highlight.start

    /**
     * 搜索命中投影（章内字符区间维度）。命中范围与当前页求交后逐行折算到文字列，
     * 连续命中列合并为矩形；同一范围自然可投影到涉及的所有页面。
     */
    inline fun projectSearchResult(
        textPage: TextPage,
        highlight: SearchHighlightOverlay,
        emit: (left: Float, top: Float, right: Float, bottom: Float, lineIndex: Int) -> Unit,
    ) = projectSearchResult(
        textPage = textPage,
        chapterIndex = highlight.chapterIndex,
        start = highlight.start,
        endExclusive = highlight.endExclusive,
        emit = emit,
    )

    /**
     * [projectSearchResult] 的标量入参形态：绘制热路径逐帧按章内区间调用，不构造包装对象。
     *
     * 页级预过滤：本页字符区间 [首行 chapterPosition, 末行末字符) 与命中区间不相交直接返回，
     * 命中在其他页的高亮不再逐列扫描本页。区间口径与 [isSearchRangeHit] 同源（章内半开区间），
     * 故早退不改变可见结果。
     */
    inline fun projectSearchResult(
        textPage: TextPage,
        chapterIndex: Int,
        start: Int,
        endExclusive: Int,
        emit: (left: Float, top: Float, right: Float, bottom: Float, lineIndex: Int) -> Unit,
    ) {
        if (endExclusive <= start) return
        if (textPage.chapterIndex != chapterIndex) return
        val lines = textPage.lines
        if (lines.isEmpty()) return
        val pageStart = lines[0].chapterPosition
        val lastLine = lines[lines.size - 1]
        val pageEndExclusive = lastLine.chapterPosition + lastLine.charSize
        if (start >= pageEndExclusive || endExclusive <= pageStart) return
        for (lineIndex in lines.indices) {
            val line = lines[lineIndex]
            val columns = line.columns
            var chapterPos = line.chapterPosition
            var runStart = -1
            var runEnd = -1
            for (colIdx in columns.indices) {
                val column = columns[colIdx]
                val length = if (column is TextColumn) column.charData.length else 1
                val columnEnd = chapterPos + length
                val hit = column is TextColumn && columnEnd > chapterPos &&
                    start < columnEnd && endExclusive > chapterPos
                if (hit) {
                    if (runStart < 0) runStart = colIdx
                    runEnd = colIdx
                } else if (runStart >= 0) {
                    emit(
                        columns[runStart].start,
                        line.lineTop,
                        columns[runEnd].end,
                        line.lineBottom,
                        lineIndex,
                    )
                    runStart = -1
                    runEnd = -1
                }
                chapterPos = columnEnd
            }
            if (runStart >= 0) {
                emit(
                    columns[runStart].start,
                    line.lineTop,
                    columns[runEnd].end,
                    line.lineBottom,
                    lineIndex,
                )
            }
        }
    }

    /**
     * 静态区间高亮投影 (章内字符区间维度, 算法同 [projectSearchResult]): 命中范围与当前页
     * 求交后逐行折算到文字列，连续命中列合并为矩形；同一区间自然可投影到涉及的所有页面。
     * 消息占位页的页内偏移与章内账本不同源，不投影。
     *
     * 绘制热路径逐帧调用，走 [projectSearchResult] 的标量入参形态：不构造包装对象，
     * 页级预过滤在投影内部完成。
     */
    inline fun projectHighlight(
        textPage: TextPage,
        highlight: HighlightOverlay,
        emit: (left: Float, top: Float, right: Float, bottom: Float, lineIndex: Int) -> Unit,
    ) {
        if (textPage.isMsgPage) return
        projectSearchResult(
            textPage = textPage,
            chapterIndex = highlight.chapterIndex,
            start = highlight.start,
            endExclusive = highlight.endExclusive,
            emit = emit,
        )
    }

    /**
     * 命中批注的页内锚定矩形 (首个投影矩形): 轻点批注弹气泡时, 气泡锚点取该矩形
     * (与绘制同走 [projectHighlight] 投影, 锚点即所见色块)。无投影 (区间为空/消息页) 返回 null。
     */
    fun projectHighlightAnchorRect(textPage: TextPage, highlight: HighlightOverlay): Rect? {
        if (textPage.isMsgPage) return null
        var anchor: Rect? = null
        projectHighlight(textPage, highlight) { l, t, r, b, _ ->
            if (anchor == null) anchor = Rect(l, t, r, b)
        }
        return anchor
    }
}
