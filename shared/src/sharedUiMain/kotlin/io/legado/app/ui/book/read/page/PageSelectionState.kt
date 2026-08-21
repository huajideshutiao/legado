package io.legado.app.ui.book.read.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn

/**
 * 选择位置（页/行/列）。对照 app 端 [io.legado.app.ui.book.read.page.entities.TextPos]：
 * [pagePos] 即原版 relativePagePos（0=当前页 / 1=下一页 / 2=下下页）——滚动模式视口内
 * 同时可见多页，选区可跨页；非滚动模式恒为 0。
 */
data class PageSelPos(
    val pagePos: Int,
    val lineIndex: Int,
    val columnIndex: Int,
) {
    /** 对照 app 端 `TextPos.isSelected()`：只看行列，pagePos 不参与 */
    val isValid: Boolean get() = lineIndex >= 0 && columnIndex >= 0

    /**
     * 对照 app 端 `TextPos.compare`：页差 -3/3 → 行差 -2/2 → 列差 -1/1 → 相等 0。
     * 量级被调用方当"差一列/差一行"的判据用（见 [PageSelectionState.selectedText]），
     * 不能压成 -1/0/1。
     */
    fun compareTo(other: PageSelPos): Int = when {
        pagePos < other.pagePos -> -3
        pagePos > other.pagePos -> 3
        lineIndex < other.lineIndex -> -2
        lineIndex > other.lineIndex -> 2
        columnIndex < other.columnIndex -> -1
        columnIndex > other.columnIndex -> 1
        else -> 0
    }

    companion object {
        /** 对照 app 端 `TextPos.reset()`：relativePagePos 归 0、行列归 -1 */
        val EMPTY = PageSelPos(0, -1, -1)
    }
}

/**
 * 三页视图访问器（对照 app 端 `ContentTextView.relativePage` / `relativeOffset` /
 * `callBack.isScroll`）：选择状态机据此按 [PageSelPos.pagePos] 取页与该页相对视口的偏移。
 * 由阅读视图层组合期注入 [PageSelectionState.pageSource]。
 */
interface SelectionPageSource {

    /** 滚动模式（对照 `callBack.isScroll`）：false 时只有第 0 页在屏，选择不越页 */
    val isScroll: Boolean

    /** 视口可见高度 px（对照 `ChapterProvider.visibleHeight`）：后续页顶越过即不参与命中 */
    val visibleHeight: Float

    /** 按 pagePos 取页（对照 `relativePage`：0=当前 / 1=下一 / 2=下下） */
    fun pageAt(pagePos: Int): TextPage?

    /** 页相对视口偏移（对照 `relativeOffset`：滚动偏移 + 前面各页页高之和） */
    fun relativeOffset(pagePos: Int): Float
}

/**
 * 页内文字选择状态机（Compose 阅读层版）。
 *
 * 对照 app 端 View 链的职责划分：
 * - [longPressStart] ← 旧 `ReadView.onLongPress` → `ContentTextView.longPress`（严格列命中）+ BreakIterator 词级展开
 * - [extendTo] ← 旧 `ReadView.selectText` → `ContentTextView.selectText/selectStartMoveIndex/selectEndMoveIndex`（粗命中 + 起止钳制）
 * - [cancel] ← 旧 `ContentTextView.cancelSelect`（ACTION_DOWN 点按取消 / 翻页清除——旧版翻页清选择
 *   是 moveToNextPage 显式 cancelSelect + setContent 换页实例后旧 selected 标志随实例废弃，
 *   upContent 链本身不含 cancelSelect）
 * - [selectedText] ← 旧 `ContentTextView.getSelectedText`
 * - [markColumns] ← 旧 `ContentTextView.upSelectChars`（区间内 TextColumn.selected = true，驱动高亮绘制）
 *
 * # 页维度（滚动模式跨页扩选）
 *
 * 位置带 [PageSelPos.pagePos]（= 旧 relativePagePos），页与页偏移经 [pageSource] 取。
 * 命中/标记/清除按旧版 `last = if (isScroll) 2 else 0` 遍历 0..last 三页：非滚动模式
 * 恒只作用于第 0 页（对照旧 `touchRough` 的 `if (!callBack.isScroll) return`）。
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

    /**
     * 图片长按菜单是否显示中（对照旧 `ReadView.isImageMenuShowing`）。
     *
     * 图片长按不产生选区，但平台浮动菜单同样要"点别处即关"，故与选区共用同一条取消链路：
     * 手势层按下时按 `isActive || imageMenuShowing` 判定，[cancel] 一并清除。
     */
    var imageMenuShowing by mutableStateOf(false)

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

    /**
     * 三页访问器（对照旧 `ContentTextView` 持有的 pageFactory + pageOffset + callBack.isScroll）：
     * 由阅读视图层组合期注入。未注入时全部入口回落"只认第 0 页"（等价旧单页模型）。
     */
    var pageSource: SelectionPageSource? = null

    /** 选择创建时的第 0 页实例（翻页后旧页数据不再被改写；也是 [currentPage] 的来源） */
    private var anchorPage: TextPage? = null

    /** 当前选择所属的页实例（只读视图，供外部判断选区是否仍位于当前页） */
    val currentPage: TextPage? get() = anchorPage

    /**
     * 本轮选择标记过的页（最多 3 个，按引用去重）：页滚出 0..2 窗口后按此清残留高亮
     * （旧版 cancelSelect 只清当时的 0..last 三页，滚出窗口的页会残留）。
     * 用 list + 引用比较而非 HashSet：TextPage 是 data class，hashCode 会深哈希整页列数据，
     * 拖拽热路径每帧入集合就是一次整页遍历。
     */
    private val markedPages = ArrayList<TextPage>(3)

    /** 长按命中位置（对照旧 `ReadView.initialTextPos`，拖拽扩选的方向基准） */
    private var initialPos = PageSelPos.EMPTY

    /** 起点手柄反转标志（对照旧 `ContentTextView.reverseStartCursor`：起点手柄拖过头后
     *  职责互换为驱动终点，见 [moveStartTo]；手柄抬手时经 [resetReverseCursor] 复位） */
    var reverseStartCursor = false
        private set

    /** 终点手柄反转标志（对照旧 `ContentTextView.reverseEndCursor`，见 [moveEndTo]） */
    var reverseEndCursor = false
        private set

    // region 命中判定

    /**
     * 严格列命中（对照旧 ContentTextView.longPress → touch()）：行命中 + 列命中才返回。
     * 三页相对遍历，非滚动模式只判第 0 页（守卫与旧 touch 逐条一致）。
     */
    private fun hitStrict(x: Float, y: Float): RoughHit? {
        val source = pageSource
        for (pagePos in 0..2) {
            val relativeOffset = relativeOffset(pagePos)
            if (pagePos > 0) {
                if (source == null || !source.isScroll) return null
                if (relativeOffset >= source.visibleHeight) return null
            }
            val page = pageAt(pagePos) ?: continue
            for (lineIndex in page.lines.indices) {
                val textLine = page.getLine(lineIndex)
                if (!textLine.isTouch(x, y, relativeOffset)) continue
                for (charIndex in textLine.columns.indices) {
                    val column = textLine.getColumn(charIndex)
                    if (column.isTouch(x)) {
                        return RoughHit(PageSelPos(pagePos, lineIndex, charIndex), column)
                    }
                }
                return null
            }
        }
        return null
    }

    /**
     * 粗命中（对照旧 ContentTextView.touchRough）：只判行命中，列未命中时
     * 回落到行首（-1）/行尾（lastIndex+1），供拖拽扩选用。
     * 三页相对遍历（守卫同 [hitStrict]），双页排版按 [TextLine.isLeftLine] 与 x 所在半页
     * 过滤（旧 touchRough 分支）。
     */
    private fun hitRough(x: Float, y: Float, viewWidth: Float): RoughHit? {
        val source = pageSource
        val halfWidth = viewWidth / 2f
        for (pagePos in 0..2) {
            val relativeOffset = relativeOffset(pagePos)
            if (pagePos > 0) {
                if (source == null || !source.isScroll) return null
                if (relativeOffset >= source.visibleHeight) return null
            }
            val page = pageAt(pagePos) ?: continue
            for (lineIndex in page.lines.indices) {
                val textLine = page.getLine(lineIndex)
                if (!textLine.isTouchY(y, relativeOffset)) continue
                if (page.doublePage) {
                    if (textLine.isLeftLine && x > halfWidth) continue
                    if (!textLine.isLeftLine && x < halfWidth) continue
                }
                val columns = textLine.columns
                for (charIndex in columns.indices) {
                    val column = columns[charIndex]
                    if (column.isTouch(x)) {
                        return RoughHit(PageSelPos(pagePos, lineIndex, charIndex), column)
                    }
                }
                // 防御: 空列行 (异常排版产物) 时 first()/last() 抛 NoSuchElementException,
                // 会杀死本手势协程导致后续长按选择全部失效, 这里按行首回落
                val firstColumn = columns.firstOrNull() ?: continue
                val isLast = firstColumn.start < x
                val charIndex = if (isLast) columns.lastIndex + 1 else -1
                return RoughHit(
                    PageSelPos(pagePos, lineIndex, charIndex),
                    if (isLast) columns.last() else firstColumn,
                )
            }
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
     * 命中页由 [pageSource] 三页遍历确定（对照旧 touch 的 relativePos 0..2），
     * 词级展开只在命中页内（对照旧 onLongPress 的 `relativePage(textPos.relativePagePos)`）。
     *
     * @param x/y 正文区坐标（调用方已减状态栏 + 页眉折算，滚动偏移由本类内部折算）
     * @return true = 已激活文字选择
     */
    fun longPressStart(
        x: Float,
        y: Float,
    ): Boolean {
        // 上一轮选择未清理时先清（防御：常规路径下点按已 cancel）
        if (isActive || anchorPage != null) clearColumns()
        val hit = hitStrict(x, y) ?: return false
        if (hit.column !is TextColumn) return false
        val page = pageAt(hit.pos.pagePos) ?: return false
        val (wordStart, wordEnd) = wordRangeAt(page, hit.pos)
        anchorPage = pageAt(0)
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
     *
     * @param x/y 正文区坐标（滚动偏移由本类按各页 relativeOffset 内部折算）
     */
    fun extendTo(x: Float, y: Float, viewWidth: Float) {
        if (!isActive) return
        val hit = hitRough(x, y, viewWidth) ?: return
        val compare = hit.pos.compareTo(initialPos)
        when {
            compare > 0 -> {
                start = initialPos
                end = clampEnd(hit.pos)
            }

            else -> {
                start = clampStart(hit.pos)
                end = clampEnd(
                    PageSelPos(
                        initialPos.pagePos,
                        initialPos.lineIndex,
                        initialPos.columnIndex - 1,
                    )
                )
            }
        }
        markColumns()
        tick++
    }

    /**
     * 起点手柄拖动（对照旧 `ContentTextView.selectStartMove`）：粗命中后按与终点的关系
     * 更新起点；拖过头（命中在终点之后）时以 x 前移两倍手柄宽二次粗命中确认，仍越过
     * 终点才左右手柄互换职责（[reverseStartCursor] 置位、[reverseEndCursor] 复位，调用方
     * 按 [reverseStartCursor] 把后续 MOVE 分流到 [moveEndTo]）。
     *
     * @param handleWidthPx 手柄像素宽（对照旧 `cursorWidth` = 24.dpToPx，反转判定用）
     */
    fun moveStartTo(
        x: Float,
        y: Float,
        handleWidthPx: Float,
        viewWidth: Float,
    ) {
        if (!isActive) return
        val hit = hitRough(x, y, viewWidth) ?: return
        // 同位置短路（对照旧 selectStartMove 首行 compare == 0 返回）
        if (hit.pos.compareTo(start) == 0) return
        if (hit.pos.compareTo(end) <= 0) {
            // 未拖过头：起点移到命中位置（对照旧 selectStartMoveIndex 的 max(0, charIndex)）
            start = clampStart(hit.pos)
        } else {
            // 拖过头：二次粗命中（x 前移两倍手柄宽）仍越过终点才反转
            // （对照旧 touchRough(x - 2 * cursorWidth) + textPos > selectEnd 判据）
            val check = hitRough(x - 2 * handleWidthPx, y, viewWidth) ?: return
            if (check.pos.compareTo(end) <= 0) return
            reverseStartCursor = true
            reverseEndCursor = false
            // 互换职责：终点并到起点位置（含原终点列，对照旧 selectEnd.columnIndex++），
            // 起点移到拖动位置（对照旧 selectStartMoveIndex(selectEnd) + selectEndMoveIndex(textPos)
            // —— 旧代码内层 touchRough 的 textPos 遮蔽外层同名参数，取的是二次粗命中位置）
            end = PageSelPos(end.pagePos, end.lineIndex, end.columnIndex + 1)
            start = clampStart(end)
            end = clampEnd(check.pos)
        }
        markColumns()
        tick++
    }

    /**
     * 终点手柄拖动（对照旧 `ContentTextView.selectEndMove`）：粗命中后按与起点的关系
     * 更新终点；拖过头（命中在起点之前）时以 x 后移两倍手柄宽二次粗命中确认，仍越过
     * 起点才左右手柄互换职责（[reverseEndCursor] 置位、[reverseStartCursor] 复位，调用方
     * 按 [reverseEndCursor] 把后续 MOVE 分流到 [moveStartTo]）。
     */
    fun moveEndTo(
        x: Float,
        y: Float,
        handleWidthPx: Float,
        viewWidth: Float,
    ) {
        if (!isActive) return
        val hit = hitRough(x, y, viewWidth) ?: return
        // 同位置短路（对照旧 selectEndMove 首行 compare == 0 返回）
        if (hit.pos.compareTo(end) == 0) return
        if (hit.pos.compareTo(start) >= 0) {
            // 未拖过头：终点移到命中位置（对照旧 selectEndMoveIndex 的 min(charIndex, lastIndex)）
            end = clampEnd(hit.pos)
        } else {
            // 拖过头：二次粗命中（x 后移两倍手柄宽）仍越过起点才反转
            // （对照旧 touchRough(x + 2 * cursorWidth) + textPos < selectStart 判据）
            val check = hitRough(x + 2 * handleWidthPx, y, viewWidth) ?: return
            if (check.pos.compareTo(start) >= 0) return
            reverseEndCursor = true
            reverseStartCursor = false
            // 互换职责：起点并到终点位置（含原起点列，对照旧 selectStart.columnIndex--），
            // 终点移到拖动位置（对照旧 selectEndMoveIndex(selectStart) + selectStartMoveIndex(textPos)
            // —— 旧代码内层 touchRough 的 textPos 遮蔽外层同名参数，取的是二次粗命中位置）
            start = PageSelPos(start.pagePos, start.lineIndex, start.columnIndex - 1)
            end = clampEnd(start)
            start = clampStart(check.pos)
        }
        markColumns()
        tick++
    }

    /** 复位手柄反转标志（对照旧 `ContentTextView.resetReverseCursor`：手柄抬手时调用） */
    fun resetReverseCursor() {
        reverseStartCursor = false
        reverseEndCursor = false
    }

    /**
     * 程序化设置选区（全文搜索跳转用，对照旧 `ContentTextView.selectStartMoveIndex` +
     * `selectEndMoveIndex` + `upSelectChars` 的最终状态）。一次性设置起止并按
     * [markColumns] 覆盖 selected 标记；[markSearchResult] 为 true 时 [page] 内选中列
     * 同时标记 [TextColumn.isSearchResult]（对照旧 upSelectChars 的
     * `column.isSearchResult = selected && isSelectingSearchResult`）。
     *
     * @param page 选区所在页，须是 [startPos] 的 pagePos 对应页（搜索跳转恒为当前页 = 0）；
     *        [pageSource] 尚未注入时用它兜底，行钳制与 isSearchResult 覆盖也按它做
     */
    fun selectRange(
        page: TextPage,
        startPos: PageSelPos,
        endPos: PageSelPos,
        markSearchResult: Boolean = false,
    ) {
        // 上一轮选择未清理时先清（防御：与 longPressStart 同款守卫）
        if (isActive || anchorPage != null) clearColumns()
        // 搜索跳转恒作用于当前页（pagePos 0）：anchorPage 直接取传入页，
        // [pageSource] 未注入时 [pageAt] 也据此回落，标记/清除仍落在同一页
        anchorPage = page
        initialPos = startPos
        start = clampStart(startPos)
        // 行越界钳制到页内（旧版算法保证 addLine=1 时 lineIndex+1 不越界，此处兜底）
        val safeEnd = PageSelPos(
            endPos.pagePos,
            endPos.lineIndex.coerceIn(0, page.lines.lastIndex),
            endPos.columnIndex,
        )
        end = clampEnd(safeEnd)
        markColumns()
        if (markSearchResult) {
            // 对照旧 upSelectChars 的覆盖语义：整页重算 isSearchResult（未选中列同步清除），
            // 命中列同步加入 page.searchResult（旧版 `textPage.searchResult.add(column)`）——
            // 否则该集合恒空，ReadBookShared.clearSearchResult 依赖它清标志会失效，
            // 退出搜索态后本次搜索高亮残留
            for (line in page.lines) {
                for (column in line.columns) {
                    if (column is TextColumn) {
                        column.isSearchResult = column.selected
                        if (column.isSearchResult) {
                            page.searchResult.add(column)
                        }
                    }
                }
            }
        }
        isActive = true
        tick++
    }

    /** 取消选择并清空高亮（对照旧 cancelSelect 的三页遍历）。翻页/点按/空白点击时调用。
     *  图片长按菜单标志一并清除（对照旧 onCancelSelect → isImageMenuShowing = false）。 */
    fun cancel() {
        imageMenuShowing = false
        if (!isActive && anchorPage == null) return
        clearColumns()
        anchorPage = null
        initialPos = PageSelPos.EMPTY
        start = PageSelPos.EMPTY
        end = PageSelPos.EMPTY
        isActive = false
        tick++
    }

    /**
     * 选区起点锚点（正文区坐标）：起点列中心 x + 起点行顶 y + 起点所在页的 relativeOffset。
     * 对照旧 ReadBookActivity.upSelectedStart 的 textMenuPosition（x=选区起点 x,
     * y=起点行 top + relativeOffset(relativePagePos)），供浮动文本操作菜单跟随选区定位。
     */
    fun selectionAnchor(): Offset? {
        val s = start
        if (!s.isValid) return null
        val page = pageAt(s.pagePos) ?: return null
        val line = page.getLine(s.lineIndex)
        val column = line.columns.getOrNull(s.columnIndex) ?: return null
        return Offset((column.start + column.end) / 2f, line.lineTop + relativeOffset(s.pagePos))
    }

    /**
     * 起点手柄锚点（正文区坐标）：起点列 start x（行尾越界取上一列 end）+ 起点行底 y
     * + 起点所在页的 relativeOffset。
     * 对照旧 `selectStartMoveIndex` → upSelectedStart 的游标定位（x = 列 start /
     * charIndex == 列数取列 end；y = 行底 lineBottom + relativeOffset，与菜单锚点
     * [selectionAnchor] 的 lineTop 口径不同：手柄贴行底，菜单锚点用行顶）。
     */
    fun startHandleOffset(): Offset? {
        val s = start
        if (!s.isValid) return null
        val page = pageAt(s.pagePos) ?: return null
        val line = page.getLine(s.lineIndex)
        val columns = line.columns
        val column = line.getColumn(s.columnIndex)
        val x = if (s.columnIndex < columns.size) column.start else column.end
        return Offset(x, line.lineBottom + relativeOffset(s.pagePos))
    }

    /**
     * 终点手柄锚点（正文区坐标）：终点列 end x（行首越界取上一列 start）+ 终点行底 y
     * + 终点所在页的 relativeOffset。
     * 对照旧 `selectEndMoveIndex` → upSelectedEnd（x = 列 end / charIndex == -1 取列
     * start；y = 行底 lineBottom + relativeOffset）。
     */
    fun endHandleOffset(): Offset? {
        val e = end
        if (!e.isValid) return null
        val page = pageAt(e.pagePos) ?: return null
        val line = page.getLine(e.lineIndex)
        val columns = line.columns
        val column = line.getColumn(e.columnIndex)
        val x = if (e.columnIndex > -1) column.end else column.start
        return Offset(x, line.lineBottom + relativeOffset(e.pagePos))
    }

    /**
     * 选中文本（对照旧 getSelectedText：遍历 start.pagePos..end.pagePos 三页窗口内的页，
     * 含跨行/段尾换行拼接，与旧版逐列判断完全一致） */
    fun selectedText(): String {
        val s = start
        val e = end
        if (!s.isValid || !e.isValid) return ""
        val sb = StringBuilder()
        for (pagePos in s.pagePos..e.pagePos) {
            val page = pageAt(pagePos) ?: continue
            for (lineIndex in page.lines.indices) {
                val line = page.getLine(lineIndex)
                val columns = line.columns
                for (charIndex in columns.indices) {
                    val column = columns[charIndex]
                    val pos = PageSelPos(pagePos, lineIndex, charIndex)
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
        }
        return sb.toString()
    }

    // endregion

    // region 内部实现

    /** 按 pagePos 取页（对照旧 relativePage）：[pageSource] 未注入时只认第 0 页 = 选区所在页 */
    private fun pageAt(pagePos: Int): TextPage? =
        pageSource?.pageAt(pagePos) ?: anchorPage?.takeIf { pagePos == 0 }

    /** 页相对视口偏移（对照旧 relativeOffset）：未注入 [pageSource] 时无滚动，恒 0 */
    private fun relativeOffset(pagePos: Int): Float = pageSource?.relativeOffset(pagePos) ?: 0f

    /** 参与标记/清除的最后一页（对照旧 upSelectChars/cancelSelect 的 `if (isScroll) 2 else 0`） */
    private val lastPagePos: Int get() = if (pageSource?.isScroll == true) 2 else 0

    private fun clampStart(pos: PageSelPos): PageSelPos =
        PageSelPos(pos.pagePos, pos.lineIndex, maxOf(0, pos.columnIndex))

    private fun clampEnd(pos: PageSelPos): PageSelPos {
        val page = pageAt(pos.pagePos) ?: return pos
        val line = page.getLine(pos.lineIndex)
        return PageSelPos(
            pos.pagePos,
            pos.lineIndex,
            minOf(pos.columnIndex, line.columns.lastIndex),
        )
    }

    /** 区间内 TextColumn 置 selected（对照旧 upSelectChars：遍历 0..last 三页逐列比较） */
    private fun markColumns() {
        val s = start
        val e = end
        for (pagePos in 0..lastPagePos) {
            val page = pageAt(pagePos) ?: continue
            if (markedPages.none { it === page }) markedPages.add(page)
            for (lineIndex in page.lines.indices) {
                val line = page.getLine(lineIndex)
                for (charIndex in line.columns.indices) {
                    val column = line.getColumn(charIndex)
                    if (column is TextColumn) {
                        val pos = PageSelPos(pagePos, lineIndex, charIndex)
                        column.selected = s.isValid && e.isValid &&
                            pos.compareTo(s) >= 0 && pos.compareTo(e) <= 0
                    }
                }
            }
        }
    }

    /**
     * 清空选中标志（对照旧 cancelSelect 的 0..last 三页遍历）：
     * 另清本轮标记过、但已滚出 0..2 窗口的页，否则滚回去时残留高亮。
     */
    private fun clearColumns() {
        val targets = ArrayList<TextPage>(4)
        for (pagePos in 0..lastPagePos) {
            val page = pageAt(pagePos) ?: continue
            if (targets.none { it === page }) targets.add(page)
        }
        for (page in markedPages) {
            if (targets.none { it === page }) targets.add(page)
        }
        for (page in targets) {
            for (line in page.lines) {
                for (column in line.columns) {
                    if (column is TextColumn) {
                        column.selected = false
                    }
                }
            }
        }
        markedPages.clear()
    }

    /**
     * 词级展开：返回 [start, end] 闭区间（对照旧 ReadView.onLongPress 的 BreakIterator 扫描）。
     * 只在命中页内展开（对照旧版 `page = relativePage(textPos.relativePagePos)`），
     * 起止沿用命中位置的 [PageSelPos.pagePos]（对照旧版 startPos/endPos = textPos.copy()）。
     */
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
        return PageSelPos(tap.pagePos, units[wordStart].lineIndex, units[wordStart].columnIndex) to
            PageSelPos(tap.pagePos, units[wordEnd].lineIndex, units[wordEnd].columnIndex)
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
