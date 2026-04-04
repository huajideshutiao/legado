package io.legado.app.ui.widget.code

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.Rect
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.util.SparseArray
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.ColorInt
import androidx.core.graphics.toColorInt
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.widget.text.ScrollMultiAutoCompleteTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TreeMap
import java.util.regex.Pattern
import kotlin.math.roundToInt

@Suppress("unused")
class CodeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ScrollMultiAutoCompleteTextView(context, attrs) {

    private var tabWidth = 0
    private var tabWidthInCharacters = 0
    private var modified = true
    var highlightWhileTextChanging = true
    private var textVersion = 0
    private var hasErrors = false
    var removeErrorsWhenTextChanged = true
    private var lastChangeStart = 0
    private var lastChangeBefore = 0
    private var lastChangeCount = 0
    private var dirtyStart = -1
    private var dirtyEnd = -1

    var autoCompleteTokenizer: Tokenizer? = null
    var autoCompleteAdapter: AutoCompleteAdapter? = null
    private val displayDensity = resources.displayMetrics.density
    private val mErrorHashSet = TreeMap<Int, Int>()
    private val mSyntaxPatternMap = mutableMapOf<Pattern, Int>()
    private var mIndentCharacterList = mutableSetOf('{', '(', '[', '+', '-', '*', '/', '=')
    private var mClosePairMap = mutableSetOf('}', ')', ']')

    var isLineNumberEnabled = false
        set(value) {
            if (field != value) {
                field = value
                if (value && enterPosSize == 0 && !editableText.isNullOrEmpty()) {
                    getEnterPos(editableText)
                }
                updateLineNumberPadding()
                postInvalidate()
            }
        }
    var lineNumberTextColor = context.secondaryTextColor
        set(value) {
            field = value
            mLineDividerPaint.color = value
            mLineNumberPaint.color = value
            postInvalidate()
        }
    var lineNumberTextSize = textSize * 0.6f
        set(value) {
            field = value
            updateLineNumberPadding()
            postInvalidate()
        }
    private val mLineNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mLineDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mLineNumberPadding = 0
    private var defaultPaddingLeft: Int? = null
    private val lineNumberCache = SparseArray<String>()

    private var enterPos = IntArray(100)
    private var enterPosSize = 0

    // 查找替换相关
    private var searchKeyword: String = ""
    private var useRegex: Boolean = false
    private var matchCase: Boolean = false
    private var matchWholeWord: Boolean = false
    private var cachedSearchPattern: Pattern? = null
    private var matchRanges = mutableListOf<Pair<Int, Int>>()
    private var currentMatchIndex = -1
    var onSearchReplaceAction: ((String) -> Unit)? = null

    // 查找替换背景色
    private val searchHighlightColor = "#80FFFF00".toColorInt() // 半透明黄
    private val currentMatchColor = context.accentColor

    private var isHighlighting = false
    private var highlightJob: Job? = null
    private val allSyntaxSpans = ArrayList<SyntaxSpan>()
    private val activeSyntaxSpans = mutableMapOf<SyntaxSpan, SyntaxForegroundColorSpan>()
    private val activeSearchSpans = mutableListOf<BackgroundColorSpan>()
    private val activeErrorSpans = mutableListOf<BackgroundColorSpan>()
    private var currentHighlightRange = Pair(-1, -1)
    private val visibleRect = Rect()

    private val updateVisibleSpansRunnable = Runnable {
        updateVisibleSpans()
    }

    private val scrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
        if (!getLocalVisibleRect(visibleRect)) {
            removeCallbacks(updateVisibleSpansRunnable)
            return@OnScrollChangedListener
        }

        val layout = layout ?: return@OnScrollChangedListener
        val firstLine = layout.getLineForVertical(visibleRect.top - paddingTop)
        val lastLine = layout.getLineForVertical(visibleRect.bottom - paddingTop)

        val startLine = kotlin.math.max(0, firstLine - 10)
        val endLine = kotlin.math.min(layout.lineCount - 1, lastLine + 10)

        val startOffset = layout.getLineStart(startLine)
        val endOffset = layout.getLineEnd(endLine)

        if (startOffset >= currentHighlightRange.first && endOffset <= currentHighlightRange.second) {
            return@OnScrollChangedListener
        }

        removeCallbacks(updateVisibleSpansRunnable)
        post(updateVisibleSpansRunnable)
    }

    private val highlightRunnable = Runnable {
        if (modified) {
            val dStart = dirtyStart
            val dEnd = dirtyEnd
            dirtyStart = -1
            dirtyEnd = -1
            if (dStart != -1 && dEnd != -1) {
                highlightSyntaxIncremental(editableText, dStart, dEnd)
            } else {
                reHighlightSyntax()
            }
        }
    }

    private fun Editable.setHighlightSpanSafe(range: Pair<Int, Int>, color: Int) {
        getSpans(range.first, range.second, BackgroundColorSpan::class.java).forEach {
            removeSpan(it)
        }
        setSpan(
            BackgroundColorSpan(color),
            range.first,
            range.second,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollChangedListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewTreeObserver.removeOnScrollChangedListener(scrollChangedListener)
        removeCallbacks(highlightRunnable)
        removeCallbacks(updateVisibleSpansRunnable)
        // 移除幽灵锚点，防止内存/视图泄漏
        cursorAnchor?.let { anchor ->
            val parentGroup = anchor.parent as? ViewGroup
            parentGroup?.post {
                parentGroup.removeView(anchor)
            }
        }
        cursorAnchor = null
    }

    private val mEditorTextWatcher: TextWatcher = object : TextWatcher {
        private var start = 0
        private var before = 0
        private var count = 0

        override fun beforeTextChanged(
            charSequence: CharSequence, start: Int, before: Int, count: Int
        ) {
            this.start = start
            this.before = before
            this.count = count
        }

        override fun onTextChanged(
            charSequence: CharSequence, start: Int, before: Int, count: Int
        ) {
            if (!modified) return
            textVersion++
            if (isLineNumberEnabled) {
                updateEnterPosIncremental(charSequence, start, before, count)
            }

            val offset = count - before

            if (dirtyStart != -1) {
                if (dirtyStart >= start) dirtyStart = kotlin.math.max(start, dirtyStart + offset)
                if (dirtyEnd > start) dirtyEnd = kotlin.math.max(start, dirtyEnd + offset)
            }

            if (offset != 0 && allSyntaxSpans.isNotEmpty()) {
                // 使用索引循环代替 Iterator 提高性能，并避免实时删除导致 O(N^2)
                for (i in 0 until allSyntaxSpans.size) {
                    val span = allSyntaxSpans[i]
                    if (span.start >= start) {
                        span.start = kotlin.math.max(start, span.start + offset)
                    }
                    if (span.end > start) {
                        span.end = kotlin.math.max(start, span.end + offset)
                    }
                }
            }

            if (removeErrorsWhenTextChanged) removeAllErrorLines()
        }

        override fun afterTextChanged(editable: Editable) {
            if (!modified) return
            handleTextChangeHighlight(start, before, count)
            if (isLineNumberEnabled) {
                updateLineNumberPadding()
            }
            if (searchKeyword.isNotEmpty()) {
                updateSearchHighlightIncremental(editable, start, before, count)
            }
        }

        private fun handleTextChangeHighlight(start: Int, before: Int, count: Int) {
            convertTabs(start, count)
            lastChangeStart = start
            lastChangeBefore = before
            lastChangeCount = count

            if (dirtyStart == -1) {
                dirtyStart = start
                dirtyEnd = start + count
            } else {
                dirtyStart = kotlin.math.min(dirtyStart, start)
                dirtyEnd = kotlin.math.max(dirtyEnd, start + count)
            }

            highlightJob?.cancel()
            currentHighlightRange = Pair(-1, -1)
            updateVisibleSpans()

            // 使用防抖机制，避免连续输入时频繁触发耗时的高亮计算
            removeCallbacks(highlightRunnable)
            postDelayed(highlightRunnable, 150)
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            lineBreakStyle = LineBreakConfig.LINE_BREAK_STYLE_NO_BREAK
        }
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        autoCompleteTokenizer = KeywordTokenizer()
        setTokenizer(autoCompleteTokenizer)
        autoCompleteAdapter = AutoCompleteAdapter(context).apply {
            textProvider = { text }
        }
        setAdapter(autoCompleteAdapter)
        threshold = 1
        dropDownWidth = 150 * displayDensity.toInt()
        mLineNumberPaint.textAlign = Paint.Align.RIGHT
        mLineNumberPaint.textSize = lineNumberTextSize
        mLineNumberPaint.color = lineNumberTextColor
        mLineDividerPaint.color = lineNumberTextColor and 0x00FFFFFF or 0x60000000
        mLineDividerPaint.strokeWidth = 1f * displayDensity
        filters = arrayOf(
            InputFilter { source, start, end, dest, dStart, dEnd ->
                if (!modified) return@InputFilter source
                return@InputFilter when {
                    source.substring(start, end).contains("#in") -> {
                        post {
                            setSelection(dStart + source.indexOf("#in", start) - start)
                        }
                        source.replace(Regex.fromLiteral("#in"), "")
                    }

                    end - start == 1 && source[start] == '\n' -> autoIndent(
                        source, dest, dStart, dEnd
                    )

                    else -> source
                }
            })
        addTextChangedListener(mEditorTextWatcher)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && event?.action == KeyEvent.ACTION_DOWN) {
            val start = selectionStart
            val end = selectionEnd
            if (start == end && start > 0) {
                val text = editableText
                var tmp = start
                for (i in start - 1 downTo 0) {
                    when (text[i]) {
                        ' ' -> tmp--
                        else -> break
                    }
                }
                if (tmp != start) {
                    text.delete(tmp, start)
                    return true
                }
            }
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER && event?.action == KeyEvent.ACTION_DOWN) {
            if (isPopupShowing && listSelection == -1) {
                replaceText(convertSelectionToString(adapter?.getItem(0)))
                dismissDropDown()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun performFiltering(text: CharSequence, keyCode: Int) {
        if (autoCompleteAdapter != null && autoCompleteTokenizer != null) {
            val end = if (text.isNotEmpty()) minOf(selectionStart, text.length) else 0
            val tokenStart = if (end > 0) autoCompleteTokenizer!!.findTokenStart(text, end) else 0
            val constraint = if (tokenStart <= end && text.isNotEmpty()) {
                text.subSequence(tokenStart, end)
            } else {
                ""
            }
            autoCompleteAdapter?.filter?.filter(constraint)
        } else {
            super.performFiltering(text, keyCode)
        }
    }

    override fun replaceText(text: CharSequence) {
        val adapter = autoCompleteAdapter
        if (adapter == null) {
            super.replaceText(text)
            return
        }

        val originalInput = adapter.getOriginalInput()
        val displayText = text.toString()

        if (displayText != originalInput) {
            val editable = editableText
            val tokenStart = autoCompleteTokenizer?.findTokenStart(editable, selectionStart) ?: 0
            val tokenEnd = selectionEnd

            val prefixBeforeDot = if (originalInput.contains(".")) {
                originalInput.take(originalInput.lastIndexOf(".") + 1)
            } else {
                ""
            }
            val insertText = prefixBeforeDot + displayText

            editable.replace(tokenStart, tokenEnd, insertText)

            val newCursorPos = tokenStart + insertText.length
            if (displayText.endsWith("()")) {
                setSelection(newCursorPos - 1)
            } else {
                setSelection(newCursorPos)
            }
        } else {
            super.replaceText(text)
        }
    }

    override fun enoughToFilter(): Boolean {
        return autoCompleteAdapter != null || super.enoughToFilter()
    }

    @Suppress("UselessCallOnNotNull")
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        //这里别动，父类初始化会调用它，此时searchKeyword为null
        if (currentMatchIndex >= 0 && !searchKeyword.isNullOrBlank()) {
            val range = matchRanges.getOrNull(currentMatchIndex)
            if (range != null && (selStart != range.first || selEnd != range.second)) {
                clearCurrentMatchHighlight()
            }
        }
    }

    private fun clearCurrentMatchHighlight() {
        matchRanges.getOrNull(currentMatchIndex)?.let {
            editableText.setHighlightSpanSafe(it, searchHighlightColor)
        }
        currentMatchIndex = -1
    }

    private var cursorAnchor: View? = null

    override fun showDropDown() {
        if (adapter == null) {
            super.showDropDown()
            return
        }

        val position = selectionStart
        val layout = layout ?: return super.showDropDown()
        val line = layout.getLineForOffset(position)

        // 1. 获取光标所在行的顶部和底部 Y 坐标，以及 X 坐标
        val lineTop = layout.getLineTop(line)
        val lineBottom = layout.getLineBottom(line)
        val lineHeight = lineBottom - lineTop
        val targetX = layout.getPrimaryHorizontal(position).toInt() + paddingLeft - scrollX

        // 2. 获取当前 View 的父布局
        val parentGroup = parent as? ViewGroup

        if (parentGroup != null) {
            // 3. 动态初始化“幽灵锚点” View
            if (cursorAnchor == null) {
                cursorAnchor = View(context).apply {
                    id = generateViewId()
                    setBackgroundColor(Color.TRANSPARENT)
                }
                parentGroup.addView(cursorAnchor)

                // 将 AutoCompleteTextView 的下拉锚点指向这个透明 View
                this.dropDownAnchor = cursorAnchor!!.id
            }

            // 4. 让锚点不仅跟随光标，还要拥有与当前行一样的高度
            // 这样不仅向下展开时贴合底部，向上翻转时也能贴合顶部，不会遮挡你正在输入的文字！
            // 定义你想要的垂直间距 (建议在实战中把 12f 换算成 dp 转 px)
            val yMargin = 5 * displayDensity.toInt()

            // 4. 让锚点不仅跟随光标，还要在上下各“膨胀”出一段间距
            cursorAnchor?.apply {
                layoutParams = (layoutParams ?: ViewGroup.LayoutParams(1, 0)).apply {
                    width = 1
                    height = lineHeight + (yMargin * 2)
                }
                x = this@CodeView.x + targetX
                y = this@CodeView.y + lineTop + paddingTop - scrollY - yMargin
            }
        }
        super.showDropDown()
    }

    private fun autoIndent(
        source: CharSequence, dest: Spanned, dStart: Int, dEnd: Int
    ): CharSequence {
        val lastNewLine = dest.lastIndexOf('\n', dStart - 1)
        val lineStart = lastNewLine + 1
        var lastNonSpaceChar: Char? = null
        for (i in dStart - 1 downTo lineStart) {
            if (!dest[i].isWhitespace()) {
                lastNonSpaceChar = dest[i]
                break
            }
        }

        var indentEnd = lineStart
        while (indentEnd < dStart && dest[indentEnd] == ' ') {
            indentEnd++
        }

        val indentStr = dest.subSequence(lineStart, indentEnd)
        val indent = StringBuilder(source).append(indentStr)
        var cursorOffset = indent.length

        if (lastNonSpaceChar in mIndentCharacterList) {
            indent.append("    ")
            cursorOffset = indent.length
            val nextChar = dest.getOrNull(dEnd)
            if (nextChar != null && nextChar in mClosePairMap) {
                indent.append('\n').append(indentStr)
            }
        } else {
            val nextChar = dest.getOrNull(dEnd)
            if (lastNonSpaceChar == nextChar && nextChar != null && nextChar in mClosePairMap) {
                indent.setLength(maxOf(0, indent.length - 4))
                cursorOffset = indent.length
            }
        }

        post { setSelection(dStart + cursorOffset) }
        return indent.toString()
    }


    private fun highlightSyntaxIncremental(editable: Editable, dStart: Int, dEnd: Int) {
        if (mSyntaxPatternMap.isEmpty()) return
        val textSnapshot = editable.toString()
        val patternMapSnapshot = HashMap(mSyntaxPatternMap)
        val capturedVersion = textVersion

        var lineStart = textSnapshot.lastIndexOf('\n', dStart - 1) + 1
        if (lineStart < 0) lineStart = 0
        var lineEnd = textSnapshot.indexOf('\n', dEnd)
        if (lineEnd < 0) lineEnd = textSnapshot.length

        isHighlighting = true
        highlightJob?.cancel()
        highlightJob = CoroutineScope(Dispatchers.Main).launch {
            val newSpans = withContext(Dispatchers.Default) {
                val result = mutableListOf<SyntaxSpan>()
                val regionText = textSnapshot.substring(lineStart, lineEnd)
                for ((pattern, color) in patternMapSnapshot) {
                    val m = pattern.matcher(regionText)
                    while (m.find()) {
                        result.add(SyntaxSpan(lineStart + m.start(), lineStart + m.end(), color))
                    }
                }
                result.sortWith(compareBy({ it.start }, { -it.end }))
                val filtered = mutableListOf<SyntaxSpan>()
                var lastMaxEnd = -1
                for (span in result) {
                    if (span.end > lastMaxEnd) {
                        filtered.add(span)
                        lastMaxEnd = span.end
                    }
                }
                filtered
            }

            if (capturedVersion != textVersion) return@launch

            allSyntaxSpans.removeAll { it.start >= lineStart && it.end <= lineEnd }
            allSyntaxSpans.addAll(newSpans)
            allSyntaxSpans.sortWith(compareBy({ it.start }, { -it.end }))

            val currentEditable = editableText
            if (currentEditable != null) {
                activeSyntaxSpans.values.forEach {
                    currentEditable.removeSpan(it)
                    recycleSpan(it)
                }
                activeSyntaxSpans.clear()
            }

            currentHighlightRange = Pair(-1, -1)
            updateVisibleSpans()
            isHighlighting = false
        }
    }

    private fun highlightSyntax(editable: Editable) {
        if (mSyntaxPatternMap.isEmpty()) return
        val textSnapshot = editable.toString()
        val patternMapSnapshot = HashMap(mSyntaxPatternMap)
        val capturedVersion = textVersion

        isHighlighting = true
        highlightJob?.cancel()
        highlightJob = CoroutineScope(Dispatchers.Main).launch {
            val newSpans = withContext(Dispatchers.Default) {
                val result = mutableListOf<SyntaxSpan>()
                for ((pattern, color) in patternMapSnapshot) {
                    val m = pattern.matcher(textSnapshot)
                    while (m.find()) {
                        result.add(SyntaxSpan(m.start(), m.end(), color))
                    }
                }
                // 按起始位置升序排列，相同起始位置时按长度降序排列
                result.sortWith(compareBy({ it.start }, { -it.end }))
                val filtered = mutableListOf<SyntaxSpan>()
                var lastMaxEnd = -1
                for (span in result) {
                    if (span.end > lastMaxEnd) {
                        filtered.add(span)
                        lastMaxEnd = span.end
                    }
                }
                filtered
            }

            if (capturedVersion != textVersion) return@launch

            val editable = editableText
            if (editable != null) {
                activeSyntaxSpans.values.forEach {
                    editable.removeSpan(it)
                    recycleSpan(it)
                }
                activeSyntaxSpans.clear()
            }

            allSyntaxSpans.clear()
            allSyntaxSpans.addAll(newSpans)

            currentHighlightRange = Pair(-1, -1)
            updateVisibleSpans()
            isHighlighting = false
        }
    }

    private val spanPool = java.util.ArrayDeque<SyntaxForegroundColorSpan>()

    private fun obtainSpan(color: Int): SyntaxForegroundColorSpan {
        val span = spanPool.poll()
        return if (span != null) {
            span.updateColor(color)
            span
        } else {
            SyntaxForegroundColorSpan(color)
        }
    }

    private fun recycleSpan(span: SyntaxForegroundColorSpan) {
        if (spanPool.size < 500) {
            spanPool.offer(span)
        }
    }

    private fun updateVisibleSpans() {
        val layout = layout ?: return
        if (!getLocalVisibleRect(visibleRect)) return

        val firstLine = layout.getLineForVertical(visibleRect.top - paddingTop)
        val lastLine = layout.getLineForVertical(visibleRect.bottom - paddingTop)

        val startLine = kotlin.math.max(0, firstLine - 10)
        val endLine = kotlin.math.min(layout.lineCount - 1, lastLine + 10)

        val startOffset = layout.getLineStart(startLine)
        val endOffset = layout.getLineEnd(endLine)

        if (startOffset >= currentHighlightRange.first && endOffset <= currentHighlightRange.second) {
            return
        }

        val renderStartLine = kotlin.math.max(0, firstLine - 20)
        val renderEndLine = kotlin.math.min(layout.lineCount - 1, lastLine + 20)
        val renderStartOffset = layout.getLineStart(renderStartLine)
        val renderEndOffset = layout.getLineEnd(renderEndLine)

        val editable = editableText ?: return

        // 1. 增量更新：移除已经不在渲染区域内的旧 Span
        val iterator = activeSyntaxSpans.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val span = entry.key
            if (span.start >= renderEndOffset || span.end <= renderStartOffset) {
                val spanInstance = entry.value
                editable.removeSpan(spanInstance)
                iterator.remove()
                recycleSpan(spanInstance)
            }
        }

        // 2. 二分查找当前区域在 allSyntaxSpans 中的起点 (基于 end 严格单调递增性质)
        var low = 0
        var high = allSyntaxSpans.size - 1
        var startIndex = 0
        while (low <= high) {
            val mid = (low + high) / 2
            if (allSyntaxSpans[mid].end <= renderStartOffset) {
                low = mid + 1
                startIndex = low
            } else {
                high = mid - 1
            }
        }

        // 3. 增量更新：遍历并添加新进入视野的 Span
        for (i in startIndex until allSyntaxSpans.size) {
            val span = allSyntaxSpans[i]
            if (span.start >= renderEndOffset) break

            if (!activeSyntaxSpans.containsKey(span)) {
                val s = kotlin.math.max(0, kotlin.math.min(span.start, editable.length))
                val e = kotlin.math.max(0, kotlin.math.min(span.end, editable.length))
                if (s < e) {
                    val newSpan = obtainSpan(span.color)
                    editable.setSpan(newSpan, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    activeSyntaxSpans[span] = newSpan
                }
            }
        }

        currentHighlightRange = Pair(renderStartOffset, renderEndOffset)
    }

    private fun highlightErrorLines(editable: Editable) {
        if (mErrorHashSet.isEmpty()) return
        val maxErrorLineValue = mErrorHashSet.lastKey()
        var lineNumber = 0
        val matcher = PATTERN_LINE.matcher(editable)
        while (matcher.find()) {
            if (mErrorHashSet.containsKey(lineNumber)) {
                val color = mErrorHashSet[lineNumber]!!
                editable.setSpan(
                    BackgroundColorSpan(color),
                    matcher.start(),
                    matcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            lineNumber += 1
            if (lineNumber > maxErrorLineValue) break
        }
    }

    private fun highlightSearch(editable: Editable) {
        if (searchKeyword.isEmpty() || matchRanges.isEmpty()) return

        matchRanges.forEachIndexed { i, range ->
            val color = if (i == currentMatchIndex) currentMatchColor else searchHighlightColor
            editable.setHighlightSpanSafe(range, color)
        }
    }

    private fun updateSearchHighlightIncremental(
        editable: Editable, start: Int, before: Int, count: Int
    ) {
        if (searchKeyword.isEmpty() || isHighlighting) return
        val offset = count - before

        // 查找受影响的匹配项：第一个结束位置在修改开始位置之后的项
        var firstAffectedIndex = matchRanges.indexOfFirst { it.second >= start }
        if (firstAffectedIndex == -1) {
            firstAffectedIndex = matchRanges.size
        }

        // 记录受影响之前的最后一个未受影响项的索引
        val prevMatchIndex = firstAffectedIndex - 1

        // 调整受影响及其之后的所有匹配项坐标（先简单偏移，后面再局部修正）
        for (i in firstAffectedIndex until matchRanges.size) {
            val range = matchRanges[i]
            matchRanges[i] = Pair(range.first + offset, range.second + offset)
        }

        // 确定局部搜索的起始位置：上个匹配元素的位置，如果没有则从0开始
        val searchStart = if (prevMatchIndex >= 0) matchRanges[prevMatchIndex].first else 0

        // 确定局部搜索的结束位置：修改后的下个匹配元素的位置，或者全文末尾
        // 注意：matchRanges 已经在上面整体偏移过了
        val nextMatchIndexAfterEdit =
            firstAffectedIndex // 因为原来的 firstAffectedIndex 现在指向了修改后的第一个元素（由于偏移）
        val searchEnd = if (nextMatchIndexAfterEdit < matchRanges.size) {
            matchRanges[nextMatchIndexAfterEdit].second
        } else {
            editable.length
        }

        // 局部移除旧的搜索高亮 Span
        editable.getSpans(searchStart, searchEnd, BackgroundColorSpan::class.java).forEach { span ->
            // 只移除搜索相关的 Span，不移除错误行等
            if (span.backgroundColor == searchHighlightColor || span.backgroundColor == currentMatchColor) {
                editable.removeSpan(span)
            }
        }

        // 局部重新执行正则搜索
        try {
            val pattern = getSearchPattern() ?: return

            val matcher = pattern.matcher(editable)
            matcher.region(searchStart, searchEnd)

            val newMatches = mutableListOf<Pair<Int, Int>>()
            while (matcher.find()) {
                newMatches.add(Pair(matcher.start(), matcher.end()))
            }

            // 更新 matchRanges：移除受影响区间内的旧项，插入新项
            val lastAffectedIndex = matchRanges.indexOfFirst { it.first >= searchEnd }
                .let { if (it == -1) matchRanges.size else it }

            if (lastAffectedIndex >= firstAffectedIndex) {
                matchRanges.subList(firstAffectedIndex, lastAffectedIndex).clear()
            }

            matchRanges.addAll(firstAffectedIndex, newMatches)

            // 重新应用 Span
            for (i in matchRanges.indices) {
                val range = matchRanges[i]
                if (range.first >= searchStart && range.second <= searchEnd) {
                    val color =
                        if (i == currentMatchIndex) currentMatchColor else searchHighlightColor
                    editable.setHighlightSpanSafe(range, color)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isTextEqual(a: CharSequence?, b: CharSequence?): Boolean {
        return android.text.TextUtils.equals(a, b)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (text.isNullOrEmpty()) {
            super.setText("", type)
            return
        }
        if (isTextEqual(this.text, text)) return

        removeAllErrorLines()
        modified = false
        super.setText(text, type)
        modified = true

        dirtyStart = -1
        dirtyEnd = -1
        allSyntaxSpans.clear()
        activeSyntaxSpans.clear()
        activeSearchSpans.clear()
        activeErrorSpans.clear()
        currentHighlightRange = Pair(-1, -1)

        val editable = editableText
        if (isLineNumberEnabled) {
            getEnterPos(editable)
            updateLineNumberPadding()
        }
        highlightErrorLines(editable)
        highlightSearch(editable)
        reHighlightSyntax()
    }

    fun setTabWidth(characters: Int) {
        if (tabWidthInCharacters == characters) return
        tabWidthInCharacters = characters
        tabWidth = (paint.measureText(" ") * characters).roundToInt()
    }

    private fun clearSpans(editable: Editable) {
        activeSyntaxSpans.values.forEach { editable.removeSpan(it) }
        activeSyntaxSpans.clear()
        editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
            .forEach(editable::removeSpan)
        editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
            .forEach(editable::removeSpan)
    }

    private fun convertTabs(start: Int, count: Int) {
        var startIndex = start
        if (tabWidth < 1) return
        val stop = startIndex + count
        while (editableText.indexOf("\t", startIndex)
                .also { startIndex = it } > -1 && startIndex < stop
        ) {
            editableText.setSpan(
                TabWidthSpan(), startIndex, startIndex + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ++startIndex
        }
    }

    fun setSyntaxPatternsMap(syntaxPatterns: Map<Pattern, Int>?) {
        mSyntaxPatternMap.clear()
        syntaxPatterns?.let {
            mSyntaxPatternMap.putAll(it)
        }
    }

    fun addSyntaxPattern(pattern: Pattern, @ColorInt color: Int) {
        mSyntaxPatternMap[pattern] = color
    }

    fun removeSyntaxPattern(pattern: Pattern) {
        mSyntaxPatternMap.remove(pattern)
    }

    fun getSyntaxPatternsSize() = mSyntaxPatternMap.size

    fun resetSyntaxPatternList() = mSyntaxPatternMap.clear()

    fun setAutoIndentCharacterList(characterList: Set<Char>) {
        mIndentCharacterList = characterList.toMutableSet()
    }

    fun clearAutoIndentCharacterList() = mIndentCharacterList.clear()

    fun getAutoIndentCharacterList() = mIndentCharacterList

    fun addErrorLine(lineNum: Int, color: Int) {
        mErrorHashSet[lineNum] = color
        hasErrors = true
    }

    fun removeErrorLine(lineNum: Int) {
        mErrorHashSet.remove(lineNum)
        hasErrors = mErrorHashSet.isNotEmpty()
    }

    fun removeAllErrorLines() {
        mErrorHashSet.clear()
        hasErrors = false
    }

    fun getErrorsSize() = mErrorHashSet.size

    fun getTextWithoutTrailingSpace() = PATTERN_TRAILING_WHITE_SPACE.matcher(text).replaceAll("")

    fun reHighlightSyntax() = highlightSyntax(editableText)

    fun reHighlightErrors() = highlightErrorLines(editableText)

    fun isHasError() = hasErrors

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
        return super.startActionMode(wrapCallback(callback))
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        return super.startActionMode(wrapCallback(callback), type)
    }

    private fun wrapCallback(callback: ActionMode.Callback?): ActionMode.Callback? {
        callback ?: return null
        return object : ActionMode.Callback2() {
            override fun onActionItemClicked(
                mode: ActionMode?, item: MenuItem?
            ): Boolean = callback.onActionItemClicked(mode, item)

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, android.R.id.custom, 1, "查找替换")
                    .setOnMenuItemClickListener {
                        val selectedText = text.substring(selectionStart, selectionEnd)
                        clearFocus()
                        onSearchReplaceAction?.invoke(selectedText)
                        true
                    }
                return callback.onCreateActionMode(mode, menu)
            }

            override fun onDestroyActionMode(mode: ActionMode?) = callback.onDestroyActionMode(mode)

            override fun onPrepareActionMode(
                mode: ActionMode?, menu: Menu?
            ): Boolean = callback.onPrepareActionMode(mode, menu)

            override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: Rect?) {
                if (callback is ActionMode.Callback2) {
                    callback.onGetContentRect(mode, view, outRect)
                } else super.onGetContentRect(mode, view, outRect)
            }
        }
    }

    private fun getSearchPattern(): Pattern? {
        if (searchKeyword.isEmpty()) {
            cachedSearchPattern = null
            return null
        }
        val flags = if (matchCase) 0 else Pattern.CASE_INSENSITIVE
        var patternStr = if (!useRegex) Pattern.quote(searchKeyword) else searchKeyword
        if (matchWholeWord) patternStr = "\\b$patternStr\\b"

        if (cachedSearchPattern?.pattern() != patternStr || cachedSearchPattern?.flags() != flags) {
            cachedSearchPattern = try {
                Pattern.compile(patternStr, flags)
            } catch (_: Exception) {
                null
            }
        }
        return cachedSearchPattern
    }

    /**
     * 在文本中查找指定关键词，支持正则表达式、大小写匹配、全词匹配等功能
     *
     * @param keyword 要查找的关键词
     * @param regex 是否使用正则表达式匹配
     * @param matchCase 是否区分大小写
     * @param matchWholeWord 是否匹配整个单词
     * @param forward 是否向下查找（true为向下，false为向上）
     * @param scrollToMatch 是否滚动到匹配位置
     */
    fun find(
        keyword: String,
        regex: Boolean,
        matchCase: Boolean,
        matchWholeWord: Boolean,
        forward: Boolean = false,
        scrollToMatch: Boolean = false
    ) {
        val needRecompute = this.searchKeyword != keyword || this.useRegex != regex ||
            this.matchCase != matchCase || this.matchWholeWord != matchWholeWord || matchRanges.isEmpty()

        this.searchKeyword = keyword
        this.useRegex = regex
        this.matchCase = matchCase
        this.matchWholeWord = matchWholeWord

        if (keyword.isEmpty()) {
            clearSearch()
            return
        }

        if (needRecompute) reHighlightSearch()
        if (matchRanges.isEmpty()) return

        val prevIndex = currentMatchIndex
        if (needRecompute || currentMatchIndex == -1) {
            val cursorPos = selectionStart
            currentMatchIndex = if (forward) {
                matchRanges.indexOfFirst { it.first >= cursorPos }.let { if (it == -1) 0 else it }
            } else {
                matchRanges.indexOfLast { it.second <= cursorPos }
                    .let { if (it == -1) matchRanges.size - 1 else it }
            }
        } else {
            currentMatchIndex = if (forward) {
                (currentMatchIndex + 1) % matchRanges.size
            } else {
                (currentMatchIndex - 1 + matchRanges.size) % matchRanges.size
            }
        }

        if (scrollToMatch || !needRecompute) {
            focusCurrentMatch()
            if (prevIndex >= 0 && prevIndex < matchRanges.size) {
                updateMatchHighlight(prevIndex, currentMatchIndex)
            } else {
                updateSingleMatchHighlight(currentMatchIndex)
            }
        }
    }

    private fun updateSingleMatchHighlight(newIndex: Int) {
        matchRanges.getOrNull(newIndex)?.let {
            editableText.setHighlightSpanSafe(it, currentMatchColor)
        }
    }

    private fun updateMatchHighlight(prevIndex: Int, newIndex: Int) {
        matchRanges.getOrNull(prevIndex)?.let {
            editableText.setHighlightSpanSafe(it, searchHighlightColor)
        }
        matchRanges.getOrNull(newIndex)?.let {
            editableText.setHighlightSpanSafe(it, currentMatchColor)
        }
    }

    private fun focusCurrentMatch() {
        if (currentMatchIndex in matchRanges.indices) {
            val range = matchRanges[currentMatchIndex]
            setSelection(range.first, range.second)
            post {
                requestFocus()
                bringPointIntoView(range.first)
            }
        }
    }

    fun replace(
        keyword: String,
        regex: Boolean,
        matchCase: Boolean,
        matchWholeWord: Boolean,
        replaceText: String
    ) {
        val needFind =
            currentMatchIndex !in matchRanges.indices || this.searchKeyword != keyword || this.useRegex != regex || this.matchCase != matchCase || this.matchWholeWord != matchWholeWord

        if (needFind) {
            find(keyword, regex, matchCase, matchWholeWord, true)
            return
        }

        if (currentMatchIndex in matchRanges.indices) {
            val range = matchRanges[currentMatchIndex]
            editableText.replace(range.first, range.second, replaceText)
            find(searchKeyword, useRegex, matchCase, matchWholeWord, true, true)
        }
    }

    fun replaceAll(
        replaceText: String
    ) {
        if (matchRanges.isEmpty()) return
        var savedCursorPos = selectionStart

        beginBatchEdit()
        modified = false // 暂时禁用 TextWatcher 中的增量计算逻辑

        // 倒序替换，避免坐标错位
        for (i in matchRanges.indices.reversed()) {
            val range = matchRanges[i]
            editableText.replace(range.first, range.second, replaceText)
            if (savedCursorPos != -1 && savedCursorPos > range.first) {
                val diff = (range.second - range.first) - replaceText.length
                savedCursorPos -= if (savedCursorPos >= range.second) diff else (savedCursorPos - range.first)
            }
        }

        modified = true
        endBatchEdit()
        textVersion++
        // 替换完成后手动触发一次状态更新
        getEnterPos(editableText)
        updateLineNumberPadding()
        setSelection(savedCursorPos)
        reHighlightSearch()
        reHighlightSyntax()
    }

    fun clearSearch() {
        editableText.getSpans(0, editableText.length, BackgroundColorSpan::class.java)
            .forEach { span ->
                if (span.backgroundColor == searchHighlightColor || span.backgroundColor == currentMatchColor) {
                    editableText.removeSpan(span)
                }
            }
        matchRanges.clear()
        currentMatchIndex = -1
    }

    private fun reHighlightSearch() {
        clearSearch()
        if (searchKeyword.isEmpty()) return

        try {
            val pattern = getSearchPattern() ?: return
            val matcher = pattern.matcher(editableText)
            while (matcher.find()) {
                val range = Pair(matcher.start(), matcher.end())
                matchRanges.add(range)
                editableText.setSpan(
                    BackgroundColorSpan(searchHighlightColor),
                    range.first,
                    range.second,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateLineNumberPadding() {
        if (defaultPaddingLeft == null) defaultPaddingLeft = paddingLeft
        val lineCount = enterPosSize + 1
        var lineStr = lineNumberCache.get(lineCount)
        if (lineStr == null) {
            lineStr = lineCount.toString()
            lineNumberCache.put(lineCount, lineStr)
        }
        mLineNumberPadding = if (isLineNumberEnabled && enterPosSize > 0) {
            (mLineNumberPaint.measureText(lineStr) + 16f * displayDensity).toInt()
        } else {
            defaultPaddingLeft!!
        }
        if (mLineNumberPadding != paddingLeft) {
            setPadding(mLineNumberPadding, paddingTop, paddingRight, paddingBottom)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateVisibleSpans()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isLineNumberEnabled && enterPosSize > 0) {
            canvas.getClipBounds(visibleRect)
            val firstLine =
                kotlin.math.max(0, layout.getLineForVertical(visibleRect.top - paddingTop))
            val lastLine = kotlin.math.min(
                layout.lineCount - 1,
                layout.getLineForVertical(visibleRect.bottom - paddingTop)
            )
            var lineStartOffset: Int

            val firstLineStartOffset = layout.getLineStart(firstLine)
            var currentLineIndex =
                enterPos.binarySearch(firstLineStartOffset, 0, enterPosSize).let {
                    if (it < 0) -it - 1 else it
                }

            var prevLineNumber = -1
            val x = paddingLeft - 11f * displayDensity
            val yOffset = paddingTop.toFloat()

            for (i in firstLine..lastLine) {
                lineStartOffset = layout.getLineStart(i)
                val isRealLineStart = if (lineStartOffset == 0) true else {
                    enterPos.binarySearch(lineStartOffset - 1, 0, enterPosSize) >= 0
                }
                if (isRealLineStart) {
                    while (currentLineIndex < enterPosSize && enterPos[currentLineIndex] < lineStartOffset) {
                        currentLineIndex++
                    }
                    val lineNumber = currentLineIndex
                    if (lineNumber != prevLineNumber) {
                        val y = layout.getLineBaseline(i).toFloat() + yOffset
                        val displayLineNumber = lineNumber + 1
                        var lineStr = lineNumberCache.get(displayLineNumber)
                        if (lineStr == null) {
                            lineStr = displayLineNumber.toString()
                            lineNumberCache.put(displayLineNumber, lineStr)
                        }
                        canvas.drawText(lineStr, x, y, mLineNumberPaint)
                        prevLineNumber = lineNumber
                    }
                }
            }
            val lineX = paddingLeft - 6f * displayDensity
            canvas.drawLine(
                lineX,
                visibleRect.top.toFloat() + paddingTop,
                lineX,
                visibleRect.bottom.toFloat() - paddingBottom,
                mLineDividerPaint
            )
        }
    }

    private fun getEnterPos(text: CharSequence) {
        enterPosSize = 0
        // 使用 TextUtils.indexOf 代替逐字 charAt 遍历
        // 这样可以避免在大文本上调用 toString() 产生的内存分配，也能利用 TextUtils 内部 getChars 的分块读取优化
        var index = android.text.TextUtils.indexOf(text, '\n', 0)
        while (index >= 0) {
            addEnterPos(index)
            index = android.text.TextUtils.indexOf(text, '\n', index + 1)
        }
    }

    private fun addEnterPos(pos: Int) {
        if (enterPosSize >= enterPos.size) {
            enterPos = enterPos.copyOf(enterPos.size * 2)
        }
        enterPos[enterPosSize++] = pos
    }

    private fun updateEnterPosIncremental(text: CharSequence, start: Int, before: Int, count: Int) {
        val offset = count - before
        if (before > 0) {
            var first = enterPos.binarySearch(start, 0, enterPosSize)
            if (first < 0) first = -first - 1
            var last = enterPos.binarySearch(start + before - 1, 0, enterPosSize)
            if (last < 0) last = -last - 2
            if (last >= first) {
                val numToRemove = last - first + 1
                System.arraycopy(enterPos, last + 1, enterPos, first, enterPosSize - (last + 1))
                enterPosSize -= numToRemove
            }
        }

        var firstIdx = enterPos.binarySearch(start, 0, enterPosSize)
        if (firstIdx < 0) firstIdx = -firstIdx - 1

        if (offset != 0) {
            for (i in firstIdx until enterPosSize) {
                enterPos[i] += offset
            }
        }

        val newEnters = mutableListOf<Int>()
        var idx = android.text.TextUtils.indexOf(text, '\n', start, start + count)
        while (idx >= 0) {
            newEnters.add(idx)
            idx = android.text.TextUtils.indexOf(text, '\n', idx + 1, start + count)
        }
        if (newEnters.isNotEmpty()) {
            val numNew = newEnters.size
            if (enterPosSize + numNew > enterPos.size) {
                enterPos = enterPos.copyOf((enterPosSize + numNew) * 2)
            }
            System.arraycopy(
                enterPos,
                firstIdx,
                enterPos,
                firstIdx + numNew,
                enterPosSize - firstIdx
            )
            for (i in newEnters.indices) {
                enterPos[firstIdx + i] = newEnters[i]
            }
            enterPosSize += numNew
        }
    }

    private inner class TabWidthSpan : ReplacementSpan() {
        override fun getSize(
            paint: Paint, text: CharSequence, start: Int, end: Int, fm: FontMetricsInt?
        ): Int {
            return tabWidth
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
        }
    }

    fun setAutoCompletions(completions: Map<String, List<String>>) {
        if (autoCompleteAdapter == null) {
            autoCompleteAdapter = AutoCompleteAdapter(context, completions).apply {
                textProvider = { text }
            }
            if (autoCompleteTokenizer == null) {
                autoCompleteTokenizer = KeywordTokenizer()
                setTokenizer(autoCompleteTokenizer)
            }
            setAdapter(autoCompleteAdapter)
        } else {
            autoCompleteAdapter!!.completions = completions
        }
    }

    private class SyntaxSpan(var start: Int, var end: Int, val color: Int)

    private class SyntaxForegroundColorSpan(var color: Int) : android.text.style.CharacterStyle(),
        android.text.style.UpdateAppearance {
        override fun updateDrawState(tp: android.text.TextPaint) {
            tp.color = color
        }

        fun updateColor(newColor: Int) {
            color = newColor
        }
    }

    companion object {
        private val PATTERN_LINE = Pattern.compile("(^.+$)+", Pattern.MULTILINE)
        private val PATTERN_TRAILING_WHITE_SPACE = Pattern.compile("[\\t ]+$", Pattern.MULTILINE)
    }
}
