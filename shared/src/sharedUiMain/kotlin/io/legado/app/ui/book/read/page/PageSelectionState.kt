package io.legado.app.ui.book.read.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn

/**
 * 页内选择位置（行/列）。对照 app 端 [io.legado.app.ui.book.read.page.entities.TextPos]，
 * 但去掉 relativePagePos：KMP 阅读层为单页模型（滚动模式也是整页切换），选择只作用于当前页。
 */
data class PageSelPos(
    val lineIndex: Int,
    val columnIndex: Int,
) {
    val isValid: Boolean get() = lineIndex >= 0 && columnIndex >= 0

    /**
     * 对照 app 端 `TextPos.compare` 去掉 relativePagePos 维度后的比较：
     * -2/-1/0/1/2（行差优先，同行比列差）。
     */
    fun compareTo(other: PageSelPos): Int = when {
        lineIndex < other.lineIndex -> -2
        lineIndex > other.lineIndex -> 2
        columnIndex < other.columnIndex -> -1
        columnIndex > other.columnIndex -> 1
        else -> 0
    }

    companion object {
        val EMPTY = PageSelPos(-1, -1)
    }
}

/**
 * 页内文字选择状态机（Compose 阅读层版）。
 *
 * 对照 app 端 View 链的职责划分：
 * - [longPressStart] ← 旧 `ReadView.onLongPress` → `ContentTextView.longPress`（严格列命中）+ BreakIterator 词级展开
 * - [extendTo] ← 旧 `ReadView.selectText` → `ContentTextView.selectText/selectStartMoveIndex/selectEndMoveIndex`（粗命中 + 起止钳制）
 * - [cancel] ← 旧 `ContentTextView.cancelSelect`（ACTION_DOWN 点按取消 / 翻页 upContent 清除）
 * - [selectedText] ← 旧 `ContentTextView.getSelectedText`
 * - [markColumns] ← 旧 `ContentTextView.upSelectChars`（区间内 TextColumn.selected = true，驱动高亮绘制）
 *
 * # 绘制联动
 *
 * 选择高亮由 [PageContentCanvas] 读取 `TextColumn.selected` 绘制。本类只改数据 +
 * 自增 [tick]；[PageContentCanvas] 在绘制块内读 [tick]（draw 阶段快照读，只失效绘制、
 * 不触发重组），拖拽热路径因此零重组开销（对照要求 5）。
 *
 * # 词级选中（无 BreakIterator 的等效实现）
 *
 * app 端用 `BreakIterator.getWordInstance(Locale.getDefault())` 定位词边界（旧
 * ReadView.onLongPress:431）。shared commonMain 无 java.text，改为等价字符扫描：
 * - 汉字（CJK 表意文字）：每个字符独立成词（ICU word break 对 ideograph 两侧断开）
 * - 连续字母/数字：合成一个词（ICU 的 WB 规则对拉丁字母连续成词）
 * - 标点/空白：各自独立成词（ICU 同样把分隔符单独切出）
 * 与 ICU 的已知差异：撇号/连字符不并入相邻单词（"don't" 切为 3 段而非 1 段）；
 * 假名/谚文按普通字母连续成词而非 ICU 的按书写系统分组。对中文阅读场景（逐字成词）
 * 与原版一致。
 *
 * 旧实现以"段落字符串 + 字符偏移"做边界扫描再反算行列；本实现直接在页的列序列上
 * 扫描（每 TextColumn 字符为一单元、非文本列占一单元，与旧版反算口径 `ci +=
 * charData.length / ci++` 完全一致），避免旧版"段落字符串不含换行导致词可跨段尾行"
 * 的反算越界怪癖（旧版词尾越界时回落为命中位置，本实现不会越界）。
 *
 * @param viewWidth 命中判定用页面宽度（px）：双页排版时粗命中的左右栏过滤需要
 *        `viewWidth / 2`（对照旧 touchRough 的 `width / 2`）
 */
class PageSelectionState {

    /** 选择是否激活（长按命中文字后 true；点按/翻页/取消后 false）。对照旧 `ReadView.isTextSelected` */
    var isActive by mutableStateOf(false)
        private set

    /** 选择起点（词级选中时为词首；拖拽后为当前区间的实际起点） */
    var start by mutableStateOf(PageSelPos.EMPTY)
        private set

    /** 选择终点（含终点列，对照旧 selectEndMoveIndex 的含列钳制） */
    var end by mutableStateOf(PageSelPos.EMPTY)
        private set

    /**
     * 选择变更版本号：拖拽热路径每次起止变化自增。
     * [PageContentCanvas] 绘制块内读值订阅（draw 阶段快照读）→ 变更只重绘不重组。
     */
    var tick by mutableIntStateOf(0)
        private set

    /** 选择作用的页面实例（翻页后旧页数据不再被改写） */
    private var pageRef: TextPage? = null

    /** 长按命中位置（对照旧 `ReadView.initialTextPos`，拖拽扩选的方向基准） */
    private var initialPos = PageSelPos.EMPTY

    // region 命中判定

    /** 严格列命中（对照旧 ContentTextView.longPress → touch()）：行命中 + 列命中才返回 */
    private fun hitStrict(page: TextPage, x: Float, y: Float): RoughHit? {
        for (lineIndex in page.lines.indices) {
            val textLine = page.getLine(lineIndex)
            if (!textLine.isTouch(x, y, 0f)) continue
            for (charIndex in textLine.columns.indices) {
                val column = textLine.getColumn(charIndex)
                if (column.isTouch(x)) {
                    return RoughHit(PageSelPos(lineIndex, charIndex), column)
                }
            }
            return null
        }
        return null
    }

    /**
     * 粗命中（对照旧 ContentTextView.touchRough）：只判行命中，列未命中时
     * 回落到行首（-1）/行尾（lastIndex+1），供拖拽扩选用。
     * 双页排版按 [TextLine.isLeftLine] 与 x 所在半页过滤（旧 touchRough 分支）。
     */
    private fun hitRough(page: TextPage, x: Float, y: Float, viewWidth: Float): RoughHit? {
        val halfWidth = viewWidth / 2f
        for (lineIndex in page.lines.indices) {
            val textLine = page.getLine(lineIndex)
            if (!textLine.isTouchY(y, 0f)) continue
            if (page.doublePage) {
                if (textLine.isLeftLine && x > halfWidth) continue
                if (!textLine.isLeftLine && x < halfWidth) continue
            }
            val columns = textLine.columns
            for (charIndex in columns.indices) {
                val column = columns[charIndex]
                if (column.isTouch(x)) {
                    return RoughHit(PageSelPos(lineIndex, charIndex), column)
                }
            }
            // 防御: 空列行 (异常排版产物) 时 first()/last() 抛 NoSuchElementException,
            // 会杀死本手势协程导致后续长按选择全部失效, 这里按行首回落
            val firstColumn = columns.firstOrNull() ?: continue
            val isLast = firstColumn.start < x
            val charIndex = if (isLast) columns.lastIndex + 1 else -1
            return RoughHit(
                PageSelPos(lineIndex, charIndex),
                if (isLast) columns.last() else firstColumn,
            )
        }
        return null
    }

    // endregion

    // region 状态机

    /**
     * 长按命中：严格列命中文字列后做词级展开，激活选择。
     *
     * 对照旧 `ReadView.onLongPress`（BreakIterator 词边界）+ `ContentTextView.longPress`
     * （column.selected = true）。命中图片等非文字列时返回 false，由调用方回落旧长按行为
     * （app 端旧行为是图片长按菜单，Compose 链当前回落整章选择对话框，见 ReadViewComposable）。
     *
     * @param contentOffsetY 滚动模式正文平移量（px，非滚动模式恒 0）：命中坐标先折算回页内坐标
     * @return true = 已激活页内文字选择
     */
    fun longPressStart(
        page: TextPage?,
        x: Float,
        y: Float,
        contentOffsetY: Float,
        viewWidth: Float,
    ): Boolean {
        val p = page ?: return false
        // 上一轮选择未清理时先清（防御：常规路径下点按已 cancel）
        if (isActive || pageRef != null) clearColumns()
        val hit = hitStrict(p, x, y - contentOffsetY) ?: return false
        if (hit.column !is TextColumn) return false
        val (wordStart, wordEnd) = wordRangeAt(p, hit.pos)
        pageRef = p
        initialPos = hit.pos
        start = wordStart
        end = wordEnd
        markColumns()
        isActive = true
        tick++
        return true
    }

    /**
     * 拖拽扩选：按粗命中更新终点（对照旧 `ReadView.selectText`）。
     *
     * 拖动位置在初始命中之前：起点 = 拖动位置，终点 = 初始位置前一列（不含初始列）；
     * 否则：起点 = 初始位置，终点 = 拖动位置。起止均按旧 `selectStartMoveIndex`
     * （max(0, col)）/ `selectEndMoveIndex`（min(col, lastIndex)）钳制。
     */
    fun extendTo(x: Float, y: Float, contentOffsetY: Float, viewWidth: Float) {
        val page = pageRef ?: return
        if (!isActive) return
        val hit = hitRough(page, x, y - contentOffsetY, viewWidth) ?: return
        val compare = hit.pos.compareTo(initialPos)
        when {
            compare > 0 -> {
                start = initialPos
                end = clampEnd(page, hit.pos)
            }

            else -> {
                start = clampStart(hit.pos)
                end = clampEnd(page, PageSelPos(initialPos.lineIndex, initialPos.columnIndex - 1))
            }
        }
        markColumns()
        tick++
    }

    /** 取消选择并清空当前页高亮（对照旧 cancelSelect）。翻页/点按/空白点击时调用。 */
    fun cancel() {
        if (!isActive && pageRef == null) return
        clearColumns()
        pageRef = null
        initialPos = PageSelPos.EMPTY
        start = PageSelPos.EMPTY
        end = PageSelPos.EMPTY
        isActive = false
        tick++
    }

    /**
     * 选区起点锚点（页内坐标）：起点列中心 x + 起点行顶 y。
     * 对照旧 ReadBookActivity.upSelectedStart 的 textMenuPosition（x=选区起点 x,
     * y=起点行 top），供浮动文本操作菜单跟随选区定位。
     */
    fun selectionAnchor(): Offset? {
        val page = pageRef ?: return null
        val s = start
        if (!s.isValid) return null
        val line = page.getLine(s.lineIndex)
        val column = line.columns.getOrNull(s.columnIndex) ?: return null
        return Offset((column.start + column.end) / 2f, line.lineTop)
    }

    /**
     * 命中当前位置的列（长按回落分发用，页实例由调用方传入——长按未命中文字时
     * [pageRef] 尚未建立）：命中图片列返回 [ImageColumn]（供平台弹图片长按菜单，
     * 对照旧 ContentTextView.longPress 的 ImageColumn 分支）；未命中任何列返回 null。
     */
    fun columnAt(page: TextPage?, x: Float, y: Float, contentOffsetY: Float): BaseColumn? {
        val p = page ?: return null
        for (line in p.lines) {
            if (!line.isTouch(x, y - contentOffsetY, 0f)) continue
            return line.columns.firstOrNull { it.isTouch(x) }
        }
        return null
    }

    /** 选中文本（对照旧 getSelectedText：含跨行/段尾换行拼接，与旧版逐列判断完全一致） */
    fun selectedText(): String {
        val page = pageRef ?: return ""
        val s = start
        val e = end
        if (!s.isValid || !e.isValid) return ""
        val sb = StringBuilder()
        for (lineIndex in s.lineIndex..e.lineIndex) {
            val line = page.getLine(lineIndex)
            val columns = line.columns
            for (charIndex in columns.indices) {
                val column = columns[charIndex]
                val pos = PageSelPos(lineIndex, charIndex)
                val compareStart = pos.compareTo(s)
                val compareEnd = pos.compareTo(e)
                if (column is TextColumn) {
                    when {
                        // 起点在该行行尾之后（起点 = lastIndex+1）：补行尾换行
                        compareStart == -1 -> if (
                            s.columnIndex == columns.size && charIndex == columns.lastIndex
                        ) {
                            sb.append("\n")
                        }

                        // 终点在该行行首之前（终点 = -1）：补行首换行
                        compareEnd == 1 -> if (e.columnIndex == -1 && charIndex == 0) {
                            sb.append("\n")
                        }

                        compareStart >= 0 && compareEnd <= 0 -> {
                            sb.append(column.charData)
                            if (
                                line.isParagraphEnd
                                && charIndex == columns.lastIndex
                                && compareEnd != 0
                            ) {
                                sb.append("\n")
                            }
                        }
                    }
                }
            }
        }
        return sb.toString()
    }

    // endregion

    // region 内部实现

    private fun clampStart(pos: PageSelPos): PageSelPos =
        PageSelPos(pos.lineIndex, maxOf(0, pos.columnIndex))

    private fun clampEnd(page: TextPage, pos: PageSelPos): PageSelPos {
        val line = page.getLine(pos.lineIndex)
        return PageSelPos(pos.lineIndex, minOf(pos.columnIndex, line.columns.lastIndex))
    }

    /** 区间内 TextColumn 置 selected（对照旧 upSelectChars 的 compareStart >= 0 && compareEnd <= 0） */
    private fun markColumns() {
        val page = pageRef ?: return
        val s = start
        val e = end
        for (lineIndex in page.lines.indices) {
            val line = page.getLine(lineIndex)
            for (charIndex in line.columns.indices) {
                val column = line.getColumn(charIndex)
                if (column is TextColumn) {
                    val pos = PageSelPos(lineIndex, charIndex)
                    column.selected = s.isValid && e.isValid &&
                        pos.compareTo(s) >= 0 && pos.compareTo(e) <= 0
                }
            }
        }
    }

    /** 清空当前页全部列的选中标志（对照旧 cancelSelect 的三页遍历，本实现只作用于当前页） */
    private fun clearColumns() {
        val page = pageRef ?: return
        for (line in page.lines) {
            for (column in line.columns) {
                if (column is TextColumn) {
                    column.selected = false
                }
            }
        }
    }

    /** 词级展开：返回 [start, end] 闭区间（对照旧 ReadView.onLongPress 的 BreakIterator 扫描） */
    private fun wordRangeAt(page: TextPage, tap: PageSelPos): Pair<PageSelPos, PageSelPos> {
        // 段内行范围：向上到上一段末行（不含），向下到本段末行（含）
        var paraStart = tap.lineIndex
        while (paraStart > 0 && !page.getLine(paraStart - 1).isParagraphEnd) paraStart--
        var paraEnd = tap.lineIndex
        while (paraEnd + 1 < page.lineSize && !page.getLine(paraEnd).isParagraphEnd) paraEnd++

        // 段内字符单元表：TextColumn 每字符一单元，非文本列一单元（对照旧版反算口径）
        val units = ArrayList<UnitChar>()
        for (li in paraStart..paraEnd) {
            val line = page.getLine(li)
            for (ci in line.columns.indices) {
                val column = line.getColumn(ci)
                if (column is TextColumn) {
                    for (ch in column.charData) units.add(UnitChar(li, ci, ch))
                } else {
                    units.add(UnitChar(li, ci, SEPARATOR))
                }
            }
        }
        if (units.isEmpty()) return tap to tap

        // 命中字符的单元偏移（对照旧版 cIndex = 列索引 + 上方各行 charSize 累计）
        var cIndex = 0
        var found = false
        outer@ for (li in paraStart..paraEnd) {
            val line = page.getLine(li)
            for (ci in line.columns.indices) {
                if (li == tap.lineIndex && ci == tap.columnIndex) {
                    found = true
                    break@outer
                }
                val column = line.getColumn(ci)
                cIndex += if (column is TextColumn) column.charData.length else 1
            }
        }
        if (!found || cIndex >= units.size) return tap to tap

        // 词边界展开（对照 BreakIterator：word = [wordStart, wordEnd] 含端）
        var wordStart = cIndex
        while (wordStart > 0 && !isWordBoundary(units[wordStart - 1].char, units[wordStart].char)) {
            wordStart--
        }
        var wordEnd = cIndex
        while (wordEnd + 1 < units.size && !isWordBoundary(
                units[wordEnd].char,
                units[wordEnd + 1].char
            )
        ) {
            wordEnd++
        }
        return PageSelPos(units[wordStart].lineIndex, units[wordStart].columnIndex) to
            PageSelPos(units[wordEnd].lineIndex, units[wordEnd].columnIndex)
    }

    /** 词边界判定：表意文字两侧断开；其余字符按字母数字/其他分组，组间断开 */
    private fun isWordBoundary(a: Char, b: Char): Boolean {
        if (isIdeographic(a) || isIdeographic(b)) return true
        return charClass(a) != charClass(b)
    }

    private fun charClass(c: Char): Int = when {
        c == SEPARATOR -> 0
        isIdeographic(c) -> 2
        c.isLetterOrDigit() -> 1
        else -> 0
    }

    /** 表意文字（CJK 统一表意/扩展 A/兼容区；对照 ICU word break 的 ideograph 类） */
    private fun isIdeographic(c: Char): Boolean =
        c in '\u3400'..'\u4DBF' || c in '\u4E00'..'\u9FFF' || c in '\uF900'..'\uFAFF'

    private data class RoughHit(val pos: PageSelPos, val column: BaseColumn)

    private data class UnitChar(val lineIndex: Int, val columnIndex: Int, val char: Char)

    private companion object {
        /** 非文本列占位单元（词边界按分隔符处理） */
        const val SEPARATOR = '\u0000'
    }

    // endregion
}
