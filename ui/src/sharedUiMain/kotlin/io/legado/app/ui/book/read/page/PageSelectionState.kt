package io.legado.app.ui.book.read.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.overlay.PageOverlayProjector
import io.legado.app.ui.book.read.page.overlay.SearchHighlightOverlay

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
 * 选区手柄标识（对照原版 activity_book_read.xml 的 cursor_left / cursor_right 两个 ImageView）。
 * 命中判定与拖动折算由阅读视图层给唯一一份实现，统一触摸分发器与鼠标手势层共用
 * （二者事件源不同，但手柄语义必须同源，否则一端改了另一端悄悄不跟）。
 */
enum class SelectionHandle { START, END }

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
 *
 * # 页维度（滚动模式跨页扩选）
 *
 * 位置带 [PageSelPos.pagePos]（= 旧 relativePagePos），页与页偏移经 [pageSource] 取。
 * 命中按旧版 `last = if (isScroll) 2 else 0` 遍历 0..last 三页：非滚动模式
 * 恒只作用于第 0 页（对照旧 `touchRough` 的 `if (!callBack.isScroll) return`）。
 *
 * # 绘制联动
 *
 * 本类只维护逻辑区间（[start]/[end]）+ 自增 [tick]，不再往排版产物写 `TextColumn.selected`
 * （旧 `upSelectChars` 的三页逐列标记）：高亮几何由 `PageOverlayProjector` 在绘制期按起止行列
 * 投影（见 `PageContentCanvas.projectSelectionState`）。[PageContentCanvas] 在绘制块内读 [tick]
 * （draw 阶段快照读，只失效绘制、不触发重组），拖拽热路径因此零重组、零标记扫描。
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
     * 搜索命中的外部章内区间；取消普通选区不清除，退出搜索态时显式置空。
     *
     * 它本身是 Compose state，普通/滚动分页都在 Canvas 绘制期读取，由快照系统自行
     * 失效。更新时不得再递增 [tick]，否则一次搜索变化会同时
     * 触发 state 与 tick 两条失效链；[tick] 只保留给普通选区的绘制期热路径。
     */
    var searchHighlight by mutableStateOf<SearchHighlightOverlay?>(null)
        private set

    fun updateSearchHighlight(highlight: SearchHighlightOverlay?) {
        if (searchHighlight == highlight) return
        searchHighlight = highlight
    }

    /**
     * 三页访问器（对照旧 `ContentTextView` 持有的 pageFactory + pageOffset + callBack.isScroll）：
     * 由阅读视图层组合期注入。未注入时全部入口回落"只认第 0 页"（等价旧单页模型）。
     */
    var pageSource: SelectionPageSource? = null

    /**
     * 选区各位置所属的页实例快照（下标 = pagePos，0/1/2），选区创建时记一次。
     * [pageSource] 读的是活页流：静默重排换整批实例、相邻章装载只换 next/nextPlus 流，
     * 与快照不一致就说明位置里的行号属于旧实例，见 [lineAt]。
     */
    private val anchorPages = arrayOfNulls<TextPage>(3)

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
                // 零列行（消息页文案里的空行正常产出）无列可命中，按行首回落也无处落，跳过
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
     * （旧版顺带置 column.selected，现改为只记区间、由绘制期投影）。命中图片等非文字列时返回 false，
     * 由调用方回落旧长按行为
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
        val hit = hitStrict(x, y) ?: return false
        if (hit.column !is TextColumn) return false
        val page = pageAt(hit.pos.pagePos) ?: return false
        val (wordStart, wordEnd) = wordRangeAt(page, hit.pos)
        // 本次命中的三页实例快照：之后所有位置解析都要与它对上（见 [lineAt]）
        for (pagePos in 0..2) anchorPages[pagePos] = pageAt(pagePos)
        initialPos = hit.pos
        start = wordStart
        end = wordEnd
        isActive = true
        tick++
        return true
    }

    /**
     * 拖拽扩选：按粗命中更新终点（对照旧 `ReadView.selectText`）。
     *
     * 拖动位置在初始命中之前：起点 = 拖动位置，终点 = 初始位置前一列（不含初始列）；
     * 拖动位置在初始命中之后或就是初始命中（同一列内微动）：起点 = 初始位置，终点 = 拖动位置。
     * 起止均按旧 `selectStartMoveIndex`（max(0, col)）/ `selectEndMoveIndex`
     * （min(col, lastIndex)）钳制。
     *
     * @param x/y 正文区坐标（滚动偏移由本类按各页 relativeOffset 内部折算）
     */
    fun extendTo(x: Float, y: Float, viewWidth: Float) {
        if (!isActive) return
        val hit = hitRough(x, y, viewWidth) ?: return
        // 非文字列不动选区（对照旧 selectText 的 `if (column is TextColumn)` 守卫：
        // 拖过图片/段评列时选区原地不动）
        if (hit.column !is TextColumn) return
        // 比较方向照旧 selectText 的 `initialTextPos.compare(textPos)`：相等（拖动没出初始列）
        // 必须落进"起点 = 初始位置"分支。写成 hit.compareTo(initial) 会把相等归到另一分支 ——
        // 起点被推到初始列、终点被推到前一列，起止反转：高亮多吃前一个字，两个手柄的锚点
        // 收敛到同一个字间边界（挤在选区中间），桌面端鼠标长按后任何微动都能复现
        val compare = initialPos.compareTo(hit.pos)
        when {
            compare > 0 -> {
                start = clampStart(hit.pos)
                end = clampEnd(
                    PageSelPos(
                        initialPos.pagePos,
                        initialPos.lineIndex,
                        initialPos.columnIndex - 1,
                    )
                )
            }

            else -> {
                start = initialPos
                end = clampEnd(hit.pos)
            }
        }
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
        // 选区已作废（页实例被换 / 行号越界后 clampEnd 已把终点置 EMPTY）：不能再拿
        // 无效起止参与“拖过头”比较 —— EMPTY 的 lineIndex/columnIndex 是 -1，任何命中都算
        // “拖过了”，会把反转标志置上并写回 -1 位置（靠抬手时空选区自愈，但中间帧语义错）
        if (!start.isValid || !end.isValid) return
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
        // 同 [moveStartTo]：选区已作废时不参与拖动
        if (!start.isValid || !end.isValid) return
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
        tick++
    }

    /** 复位手柄反转标志（对照旧 `ContentTextView.resetReverseCursor`：手柄抬手时调用） */
    fun resetReverseCursor() {
        reverseStartCursor = false
        reverseEndCursor = false
    }

    /**
     * 程序化设置选区（全文搜索跳转用，对照旧 `ContentTextView.selectStartMoveIndex` +
     * `selectEndMoveIndex` + `upSelectChars` 的最终状态）。一次性设置起止。
     *
     * @param page 选区所在页，须是 [startPos] 的 pagePos 对应页（搜索跳转恒为当前页 = 0）；
     *        [pageSource] 尚未注入时用它兜底，行钳制也按它做
     */
    fun selectRange(
        page: TextPage,
        startPos: PageSelPos,
        endPos: PageSelPos,
    ) {
        if (page.lines.isEmpty()) return
        // 搜索跳转恒作用于当前页（pagePos 0）：第 0 槽直接取传入页，
        // [pageSource] 未注入时 [pageAt] 也据此回落
        anchorPages[0] = page
        for (pagePos in 1..2) anchorPages[pagePos] = pageAt(pagePos)
        initialPos = startPos
        start = clampStart(startPos)
        // 行越界钳制到目标页内（对照旧 selectEndMoveIndex，支持跨页至下一页）
        val targetPage = anchorPages.getOrNull(endPos.pagePos) ?: page
        val maxLine = targetPage.lines.lastIndex.coerceAtLeast(0)
        val safeEnd = PageSelPos(
            endPos.pagePos,
            endPos.lineIndex.coerceIn(0, maxLine),
            endPos.columnIndex,
        )
        var clampedEnd = clampEnd(safeEnd)
        if (!clampedEnd.isValid && endPos.pagePos > 0) {
            clampedEnd = clampEnd(
                PageSelPos(
                    0,
                    page.lines.lastIndex,
                    page.lines.lastOrNull()?.columns?.lastIndex ?: 0
                )
            )
        }
        end = clampedEnd
        isActive = true
        tick++
    }

    /**
     * 从章内 UTF-16 半开区间程序化建立选区（全文搜索跳转用）。
     *
     * [startChapterOffset], [endChapterOffsetExclusive] 与 [SearchHighlightOverlay] 使用同一字符账本：
     * 每个 [TextColumn] 消耗 `charData.length`，非文字列消耗 1；区间相交判定直接复用
     * [PageOverlayProjector.isSearchRangeHit]。因此 UTF-16 偏移只用于推进账本，绝不会被误当成
     * 列下标；一个列即使承载代理对或组合字素，也只产生一个 [PageSelPos.columnIndex]。
     *
     * 选区页空间固定为当前页及后续两页（[SelectionPageSource] 的 0..2 窗口）。超出窗口的
     * 搜索高亮仍由章内 overlay 在翻到对应页后绘制，交互选区则钳在当前三页窗口内。
     */
    fun selectChapterRange(
        pages: List<TextPage>,
        firstPageIndex: Int,
        startChapterOffset: Int,
        endChapterOffsetExclusive: Int,
    ): Boolean {
        if (startChapterOffset < 0 || endChapterOffsetExclusive <= startChapterOffset) return false
        val firstPage = pages.getOrNull(firstPageIndex) ?: return false
        val highlight = SearchHighlightOverlay(
            chapterIndex = firstPage.chapterIndex,
            start = startChapterOffset,
            endExclusive = endChapterOffsetExclusive,
        )
        var rangeStart: PageSelPos? = null
        var rangeEnd: PageSelPos? = null
        val lastPageIndex = minOf(firstPageIndex + 2, pages.lastIndex)
        for (pageIndex in firstPageIndex..lastPageIndex) {
            val page = pages[pageIndex]
            val pagePos = pageIndex - firstPageIndex
            for (lineIndex in page.lines.indices) {
                val line = page.lines[lineIndex]
                var chapterOffset = line.chapterPosition
                for (columnIndex in line.columns.indices) {
                    val column = line.columns[columnIndex]
                    val columnLength = if (column is TextColumn) column.charData.length else 1
                    val columnEnd = chapterOffset + columnLength
                    if (column is TextColumn && PageOverlayProjector.isSearchRangeHit(
                            textPage = page,
                            highlight = highlight,
                            start = chapterOffset,
                            endExclusive = columnEnd,
                        )
                    ) {
                        val pos = PageSelPos(pagePos, lineIndex, columnIndex)
                        if (rangeStart == null) rangeStart = pos
                        rangeEnd = pos
                    }
                    chapterOffset = columnEnd
                }
            }
        }
        val startPos = rangeStart ?: return false
        val endPos = rangeEnd ?: return false
        // 直接以参与反算的同一批页建立锚点，不能再经 pageSource 回读：skipToPage 的回调
        // 可能早于 Compose 把新三页注入 pageSource，回读会把新行列绑定到旧页实例。
        for (pagePos in 0..2) {
            anchorPages[pagePos] = pages.getOrNull(firstPageIndex + pagePos)
        }
        initialPos = startPos
        start = startPos
        end = endPos
        isActive = true
        tick++
        return true
    }

    /** 取消选择（对照旧 cancelSelect）。翻页/点按/空白点击时调用：区间归零后高亮随投影消失。
     *  图片长按菜单标志一并清除（对照旧 onCancelSelect → isImageMenuShowing = false）。 */
    fun cancel() {
        imageMenuShowing = false
        if (!isActive && anchorPages[0] == null) return
        anchorPages.fill(null)
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
        val line = lineAt(s) ?: return null
        val column = line.columns.getOrNull(s.columnIndex) ?: return null
        return Offset((column.start + column.end) / 2f, line.lineTop + relativeOffset(s.pagePos))
    }

    /**
     * 起点手柄锚点（正文区坐标）：起点列 start x（起点在行尾之后时取末列 end）+ 起点行底 y
     * + 起点所在页的 relativeOffset。
     * 对照旧 `selectStartMoveIndex` → upSelectedStart 的游标定位（y = 行底 lineBottom +
     * relativeOffset，与菜单锚点 [selectionAnchor] 的 lineTop 口径不同：手柄贴行底，
     * 菜单锚点用行顶）。
     */
    fun startHandleOffset(): Offset? {
        val s = start
        if (!s.isValid) return null
        val line = lineAt(s) ?: return null
        val columns = line.columns
        // columnIndex == columns.size 是选区模型有意编码的"行尾之后"（见 [selectedText]
        // 的行尾换行分支），手柄贴末列右缘；零列行没有手柄可画
        val x = if (s.columnIndex < columns.size) {
            columns[s.columnIndex].start
        } else {
            columns.lastOrNull()?.end ?: return null
        }
        return Offset(x, line.lineBottom + relativeOffset(s.pagePos))
    }

    /**
     * 终点手柄锚点（正文区坐标）：终点列 end x + 终点行底 y + 终点所在页的 relativeOffset。
     * 对照旧 `selectEndMoveIndex` → upSelectedEnd。终点落在"行首之前"（columnIndex == -1，
     * 反向拖到行首时产生）不成选区，[PageSelPos.isValid] 已排除，不画手柄。
     */
    fun endHandleOffset(): Offset? {
        val e = end
        if (!e.isValid) return null
        val line = lineAt(e) ?: return null
        val column = line.columns.getOrNull(e.columnIndex) ?: return null
        return Offset(column.end, line.lineBottom + relativeOffset(e.pagePos))
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
    private fun pageAt(pagePos: Int): TextPage? {
        val source = pageSource ?: return anchorPages[0]?.takeIf { pagePos == 0 }
        return source.pageAt(pagePos)
    }

    /**
     * 位置所在页的行：页实例已不是选区创建时的那一个（静默重排 / 相邻章装载换了实例），
     * 或行号越出页内行数时返回 null —— 位置里的行号属于旧实例，拿它索引新页只会算出
     * 错的几何且不报错，一律按"定位未生效、选区已失效"处理。
     */
    private fun lineAt(pos: PageSelPos): TextLine? {
        val page = pageAt(pos.pagePos)?.takeIf { it === anchorPages[pos.pagePos] } ?: return null
        return page.lines.getOrNull(pos.lineIndex)
    }

    /** 页相对视口偏移（对照旧 relativeOffset）：未注入 [pageSource] 时无滚动，恒 0 */
    private fun relativeOffset(pagePos: Int): Float = pageSource?.relativeOffset(pagePos) ?: 0f

    private fun clampStart(pos: PageSelPos): PageSelPos =
        PageSelPos(pos.pagePos, pos.lineIndex, maxOf(0, pos.columnIndex))

    /** 终点列钳到行内末列；行定位未生效（页实例被换 / 行号越界）时整段终点归零让选区作废 */
    private fun clampEnd(pos: PageSelPos): PageSelPos {
        val line = lineAt(pos) ?: return PageSelPos.EMPTY
        return PageSelPos(
            pos.pagePos,
            pos.lineIndex,
            minOf(pos.columnIndex, line.columns.lastIndex),
        )
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
