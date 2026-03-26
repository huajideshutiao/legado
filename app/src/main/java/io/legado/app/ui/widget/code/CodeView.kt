package io.legado.app.ui.widget.code

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.Rect
import android.text.Editable
import android.text.InputFilter
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.toColorInt
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.widget.text.ScrollMultiAutoCompleteTextView
import java.util.TreeMap
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.roundToInt
import android.text.Layout

@Suppress("unused")
class CodeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ScrollMultiAutoCompleteTextView(context, attrs) {

    private var tabWidth = 0
    private var tabWidthInCharacters = 0
    private var modified = true
    var highlightWhileTextChanging = true
    private var hasErrors = false
    var removeErrorsWhenTextChanged = true
    private var lastChangeStart = 0
    private var lastChangeBefore = 0
    private var lastChangeCount = 0

    var autoCompleteTokenizer: Tokenizer? = null
    private val displayDensity = resources.displayMetrics.density
    private val mErrorHashSet = TreeMap<Int, Int>()
    private val mSyntaxPatternMap = mutableMapOf<Pattern, Int>()
    private var mIndentCharacterList = mutableSetOf('{', '(', '[', '+', '-', '*', '/', '=')
    private var mClosePairMap = mapOf('{' to '}', '(' to ')', '[' to ']')

    var isLineNumberEnabled = false
        set(value) {
            field = value
            updateLineNumberPadding()
            postInvalidate()
        }
    var mLineNumberTextColor = context.secondaryTextColor
        set(value) {
            field = value
            mLineDividerPaint.color = value
            mLineNumberPaint.color = value
            postInvalidate()
        }
    var mLineNumberTextSize = textSize * 0.6f
        set(value) {
            field = value
            updateLineNumberPadding()
            postInvalidate()
        }
    private val mLineNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mLineDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mLineNumberPadding = 0
    private var defaultPaddingLeft: Int? = null
    private val lineNumberCache = android.util.SparseArray<String>()

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
            if (!modified || isHighlighting) return
            if (isLineNumberEnabled) {
                updateEnterPosIncremental(charSequence, start, before, count)
            }
            if (highlightWhileTextChanging) {
                handleTextChangeHighlight(start, before, count)
            }
            if (removeErrorsWhenTextChanged) removeAllErrorLines()
        }

        override fun afterTextChanged(editable: Editable) {
            if (!modified || isHighlighting) return
            if (!highlightWhileTextChanging) {
                handleTextChangeHighlight(start, before, count)
            }
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
            highlightIncremental(lastChangeStart, lastChangeCount)
        }
    }

    init {
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        if (autoCompleteTokenizer == null) {
            autoCompleteTokenizer = KeywordTokenizer()
        }
        setTokenizer(autoCompleteTokenizer)

        mLineNumberPaint.textAlign = Paint.Align.RIGHT
        mLineNumberPaint.textSize = mLineNumberTextSize
        mLineNumberPaint.color = mLineNumberTextColor
        mLineDividerPaint.color = mLineNumberTextColor
        mLineDividerPaint.style = Paint.Style.STROKE
        mLineDividerPaint.strokeWidth = 1f * displayDensity
        mLineDividerPaint.pathEffect =
            DashPathEffect(floatArrayOf(5f * displayDensity, 5f * displayDensity), 0f)
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
                    end - start == 1 && source[start] == '\n' ->
                        autoIndent(source, dest, dStart, dEnd)
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
                        '\n' -> {
                            tmp--
                            break
                        }

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
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("UselessCallOnNotNull")
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (currentMatchIndex >= 0 && !searchKeyword.isNullOrBlank()) {
            val range = matchRanges.getOrNull(currentMatchIndex)
            if (range != null && (selStart != range.first || selEnd != range.second)) {
                clearCurrentMatchHighlight()
            }
        }
    }

    private fun clearCurrentMatchHighlight() {
        val range = matchRanges.getOrNull(currentMatchIndex) ?: return
        val editable = editableText
        if (range.first >= 0 && range.second <= editable.length && range.first < range.second) {
            editable.getSpans(range.first, range.second, BackgroundColorSpan::class.java)
                .forEach { editable.removeSpan(it) }
            editable.setSpan(
                BackgroundColorSpan(searchHighlightColor),
                range.first,
                range.second,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        currentMatchIndex = -1
    }

    override fun showDropDown() {
        val screenPoint = IntArray(2)
        getLocationOnScreen(screenPoint)
        val displayFrame = Rect()
        getWindowVisibleDisplayFrame(displayFrame)
        val position = selectionStart
        val layout = layout
        val line = layout.getLineForOffset(position)
        val verticalDistanceInDp = (750 + 140 * line) / displayDensity
        dropDownVerticalOffset = verticalDistanceInDp.toInt()
        val horizontalDistanceInDp = layout.getPrimaryHorizontal(position) / displayDensity
        dropDownHorizontalOffset = horizontalDistanceInDp.toInt()
        super.showDropDown()
    }

    private fun autoIndent(
        source: CharSequence, dest: Spanned, dStart: Int, dEnd: Int
    ): CharSequence {
        var iStart = dStart - 1
        var lastNonSpaceChar: Char? = null
        while (iStart > -1) {
            val c = dest[iStart]
            if (c == '\n') break
            if (lastNonSpaceChar == null && !c.isWhitespace()) {
                lastNonSpaceChar = c
            }
            --iStart
        }
        val lineStart = iStart + 1
        var indentEnd = lineStart
        while (indentEnd < dStart) {
            if (dest[indentEnd] != ' ') break
            indentEnd++
        }
        val indentStr = dest.subSequence(lineStart, indentEnd)
        val indent = StringBuilder(source).append(indentStr)
        // 如果上一行以特定字符结尾
        lastNonSpaceChar?.let { char ->
            if (mIndentCharacterList.contains(char)) {
                indent.append("    ")
            }
            // 如果后面跟着的是匹配的闭合符号，则多加一个换行并缩进
            mClosePairMap[char]?.let { closeChar ->
                if (dEnd < dest.length && dest[dEnd] == closeChar) {
                    indent.append("\n").append(indentStr)
                    post {
                        setSelection(
                            dStart + indent.length - indentStr.length - 1
                        )
                    }
                }
            }
        }
        return indent.toString()
    }

    private fun highlightSyntax(editable: Editable) {
        if (mSyntaxPatternMap.isEmpty()) return
        for (pattern in mSyntaxPatternMap.keys) {
            val color = mSyntaxPatternMap[pattern]!!
            val m = pattern.matcher(editable)
            while (m.find()) {
                createForegroundColorSpan(editable, m, color)
            }
        }
    }

    private fun highlightErrorLines(editable: Editable) {
        if (mErrorHashSet.isEmpty()) return
        val maxErrorLineValue = mErrorHashSet.lastKey()
        var lineNumber = 0
        val matcher = PATTERN_LINE.matcher(editable)
        while (matcher.find()) {
            if (mErrorHashSet.containsKey(lineNumber)) {
                val color = mErrorHashSet[lineNumber]!!
                createBackgroundColorSpan(editable, matcher, color)
            }
            lineNumber += 1
            if (lineNumber > maxErrorLineValue) break
        }
    }

    private fun createForegroundColorSpan(
        editable: Editable, matcher: Matcher, @ColorInt color: Int
    ) {
        editable.setSpan(
            ForegroundColorSpan(color),
            matcher.start(),
            matcher.end(),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun createBackgroundColorSpan(
        editable: Editable, matcher: Matcher, @ColorInt color: Int
    ) {
        editable.setSpan(
            BackgroundColorSpan(color),
            matcher.start(),
            matcher.end(),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun highlightSearch(editable: Editable) {
        if (searchKeyword.isEmpty() || matchRanges.isEmpty()) return

        for (i in matchRanges.indices) {
            val range = matchRanges[i]
            val color = if (i == currentMatchIndex) currentMatchColor else searchHighlightColor
            if (range.first >= 0 && range.second <= editable.length && range.first < range.second) {
                editable.setSpan(
                    BackgroundColorSpan(color),
                    range.first,
                    range.second,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun highlightIncremental(start: Int, count: Int) {
        if (isHighlighting || mSyntaxPatternMap.isEmpty()) return
        isHighlighting = true
        for ((pattern, color) in mSyntaxPatternMap) {
            val m = pattern.matcher(editableText)
            m.region(start, start + count)
            while (m.find()) {
                editableText.setSpan(
                    ForegroundColorSpan(color),
                    m.start(),
                    m.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        isHighlighting = false
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
                    editable.setSpan(
                        BackgroundColorSpan(color),
                        range.first,
                        range.second,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setTextHighlighted(text: CharSequence?) {
        if (text.isNullOrEmpty()) return
        removeAllErrorLines()
        modified = false
        val spannable = SpannableStringBuilder(text)
        clearSpans(spannable)
        highlightErrorLines(spannable)
        highlightSyntax(spannable)
        highlightSearch(spannable)
        setText(spannable)
        modified = true
    }

    fun setTabWidth(characters: Int) {
        if (tabWidthInCharacters == characters) return
        tabWidthInCharacters = characters
        tabWidth = paint.measureText(" ").roundToInt()
    }

    private fun clearSpans(editable: Editable) {
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

    fun getSyntaxPatternsSize(): Int {
        return mSyntaxPatternMap.size
    }

    fun resetSyntaxPatternList() {
        mSyntaxPatternMap.clear()
    }

    fun setAutoIndentCharacterList(characterList: Set<Char>) {
        mIndentCharacterList = characterList.toMutableSet()
    }

    fun clearAutoIndentCharacterList() {
        mIndentCharacterList.clear()
    }

    fun getAutoIndentCharacterList(): Set<Char> {
        return mIndentCharacterList
    }

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

    fun getErrorsSize(): Int {
        return mErrorHashSet.size
    }

    fun getTextWithoutTrailingSpace(): String {
        return PATTERN_TRAILING_WHITE_SPACE.matcher(text).replaceAll("")
    }

    fun reHighlightSyntax() {
        highlightSyntax(editableText)
    }

    fun reHighlightErrors() {
        highlightErrorLines(editableText)
    }

    fun isHasError(): Boolean {
        return hasErrors
    }

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
                menu.add(Menu.NONE, android.R.id.custom, 1, "查找替换").setOnMenuItemClickListener {
                    val start = selectionStart
                    val end = selectionEnd
                    val fullText = text.toString()
                    var selectedText = ""
                    if (start in 0..<end && end <= fullText.length) {
                        selectedText = fullText.substring(start, end)
                    }
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
        // 判断是否需要重新计算匹配结果
        val needRecompute =
            this.searchKeyword != keyword || this.useRegex != regex || this.matchCase != matchCase || this.matchWholeWord != matchWholeWord || matchRanges.isEmpty()

        // 更新搜索参数
        this.searchKeyword = keyword
        this.useRegex = regex
        this.matchCase = matchCase
        this.matchWholeWord = matchWholeWord

        // 如果关键词 or 文本为空，清除搜索结果并返回
        if (keyword.isEmpty() || text.isEmpty()) {
            clearSearch()
            return
        }

        // 如果需要重新计算匹配结果
        if (needRecompute) {
            matchRanges.clear()
            currentMatchIndex = -1
            try {
                val pattern = getSearchPattern() ?: return
                val matcher = pattern.matcher(text)
                // 查找所有匹配项
                while (matcher.find()) {
                    matchRanges.add(Pair(matcher.start(), matcher.end()))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 如果没有匹配结果，重新高亮并返回
        if (matchRanges.isEmpty()) {
            reHighlightSearch()
            return
        }

        // 确定当前应该选中的索引
        if (needRecompute) {
            if (scrollToMatch) {
                // 获取当前光标位置
                val cursorPos = selectionStart
                // 根据查找方向确定当前匹配索引
                if (forward) {
                    currentMatchIndex = matchRanges.indexOfFirst { it.first >= cursorPos }
                    if (currentMatchIndex == -1) currentMatchIndex = 0
                } else {
                    currentMatchIndex = matchRanges.indexOfLast { it.second <= cursorPos }
                    if (currentMatchIndex == -1) currentMatchIndex = matchRanges.size - 1
                }
                // 聚焦当前匹配项
                focusCurrentMatch()
            }
            // 重新高亮搜索结果
            reHighlightSearch()
        } else {
            // 如果不需要重新计算，直接更新当前匹配索引
            val prevIndex = currentMatchIndex
            if (currentMatchIndex == -1) {
                // 如果没有当前匹配索引，根据光标位置确定
                val cursorPos = selectionStart
                if (forward) {
                    currentMatchIndex = matchRanges.indexOfFirst { it.first >= cursorPos }
                    if (currentMatchIndex == -1) currentMatchIndex = 0
                } else {
                    currentMatchIndex = matchRanges.indexOfLast { it.second <= cursorPos }
                    if (currentMatchIndex == -1) currentMatchIndex = matchRanges.size - 1
                }
            } else {
                // 如果有当前匹配索引，根据查找方向更新索引
                currentMatchIndex = if (forward) {
                    (currentMatchIndex + 1) % matchRanges.size
                } else {
                    (currentMatchIndex - 1 + matchRanges.size) % matchRanges.size
                }
            }
            // 聚焦当前匹配项
            focusCurrentMatch()
            // 更新匹配高亮
            if (prevIndex >= 0) {
                updateMatchHighlight(prevIndex, currentMatchIndex)
            } else {
                updateSingleMatchHighlight(currentMatchIndex)
            }
        }
    }

    private fun updateSingleMatchHighlight(newIndex: Int) {
        val editable = editableText
        val newRange = matchRanges.getOrNull(newIndex) ?: return
        if (newRange.first >= 0 && newRange.second <= editable.length && newRange.first < newRange.second) {
            editable.getSpans(newRange.first, newRange.second, BackgroundColorSpan::class.java)
                .forEach { editable.removeSpan(it) }
            editable.setSpan(
                BackgroundColorSpan(currentMatchColor),
                newRange.first,
                newRange.second,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun updateMatchHighlight(prevIndex: Int, newIndex: Int) {
        val editable = editableText
        matchRanges.getOrNull(prevIndex)?.let { range ->
            if (range.first >= 0 && range.second <= editable.length && range.first < range.second) {
                editable.getSpans(range.first, range.second, BackgroundColorSpan::class.java)
                    .forEach { editable.removeSpan(it) }
                editable.setSpan(
                    BackgroundColorSpan(searchHighlightColor),
                    range.first,
                    range.second,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        matchRanges.getOrNull(newIndex)?.let { range ->
            if (range.first >= 0 && range.second <= editable.length && range.first < range.second) {
                editable.getSpans(range.first, range.second, BackgroundColorSpan::class.java)
                    .forEach { editable.removeSpan(it) }
                editable.setSpan(
                    BackgroundColorSpan(currentMatchColor),
                    range.first,
                    range.second,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
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
            val editable = editableText
            if (range.first >= 0 && range.second <= editable.length) {
                editable.replace(range.first, range.second, replaceText)
                find(
                    searchKeyword, useRegex, matchCase, matchWholeWord, true, true
                )
            }
        }
    }

    fun replaceAll(
        keyword: String,
        regex: Boolean,
        matchCase: Boolean,
        matchWholeWord: Boolean,
        replaceText: String
    ) {
        val needFind =
            matchRanges.isEmpty() || this.searchKeyword != keyword || this.useRegex != regex || this.matchCase != matchCase || this.matchWholeWord != matchWholeWord

        if (needFind) {
            find(keyword, regex, matchCase, matchWholeWord, forward = true, scrollToMatch = false)
        }

        if (matchRanges.isEmpty()) return

        val editable = editableText
        var savedCursorPos = selectionStart

        modified = false // 暂时禁用 TextWatcher 中的增量计算逻辑

        // 倒序替换，避免坐标错位
        for (i in matchRanges.indices.reversed()) {
            val range = matchRanges[i]
            if (range.first >= 0 && range.second <= editable.length) {
                editable.replace(range.first, range.second, replaceText)
                if (savedCursorPos != -1 && savedCursorPos > range.first) {
                    val diff = (range.second - range.first) - replaceText.length
                    savedCursorPos -= if (savedCursorPos >= range.second) diff else (savedCursorPos - range.first)
                }
            }
        }

        modified = true
        // 替换完成后手动触发一次状态更新
        getEnterPos(editable)
        updateLineNumberPadding()
        setSelection(savedCursorPos)
        clearSearch()
        recomputeSearchMatches()
    }

    fun clearSearch() {
        val editable = editableText
        editable.getSpans(0, editable.length, BackgroundColorSpan::class.java).forEach { span ->
            if (span.backgroundColor == searchHighlightColor || span.backgroundColor == currentMatchColor) {
                editable.removeSpan(span)
            }
        }
        searchKeyword = ""
        matchRanges.clear()
        currentMatchIndex = -1
    }

    private fun recomputeSearchMatches() {
        val editable = editableText
        // 清理旧高亮
        editable.getSpans(0, editable.length, BackgroundColorSpan::class.java).forEach { span ->
            if (span.backgroundColor == searchHighlightColor || span.backgroundColor == currentMatchColor) {
                editable.removeSpan(span)
            }
        }

        matchRanges.clear()
        currentMatchIndex = -1
        if (searchKeyword.isEmpty()) return

        try {
            val pattern = getSearchPattern() ?: return
            val matcher = pattern.matcher(editable)
            while (matcher.find()) {
                val range = Pair(matcher.start(), matcher.end())
                matchRanges.add(range)
                editable.setSpan(
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

    private fun reHighlightSearch() {
        recomputeSearchMatches()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isLineNumberEnabled && enterPosSize > 0) {
            val firstLine = layout.getLineForOffset(scrollY)
            val lastLine = layout.getLineForOffset(scrollY + height)
            var lineStartOffset: Int

            val firstLineStartOffset = layout.getLineStart(firstLine)
            var currentLineIndex =
                java.util.Arrays.binarySearch(enterPos, 0, enterPosSize, firstLineStartOffset).let {
                    if (it < 0) -it - 1 else it
                }

            var prevLineNumber = -1
            for (i in firstLine..lastLine) {
                lineStartOffset = layout.getLineStart(i)
                if (lineStartOffset == 0 || text[lineStartOffset - 1] == '\n') {
                    while (currentLineIndex < enterPosSize && enterPos[currentLineIndex] < lineStartOffset) {
                        currentLineIndex++
                    }
                    val lineNumber = currentLineIndex
                    if (lineNumber != prevLineNumber) {
                        val x = paddingLeft - 11f * displayDensity
                        val y = layout.getLineBaseline(i).toFloat() + paddingTop
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
                (scrollY + paddingTop).toFloat(),
                lineX,
                (scrollY + height - paddingBottom).toFloat(),
                mLineDividerPaint
            )
        }
    }

    private fun getEnterPos(text: CharSequence) {
        enterPosSize = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                addEnterPos(i)
            }
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
            var first = java.util.Arrays.binarySearch(enterPos, 0, enterPosSize, start)
            if (first < 0) first = -first - 1
            var last = java.util.Arrays.binarySearch(enterPos, 0, enterPosSize, start + before - 1)
            if (last < 0) last = -last - 2
            if (last >= first) {
                val numToRemove = last - first + 1
                System.arraycopy(enterPos, last + 1, enterPos, first, enterPosSize - (last + 1))
                enterPosSize -= numToRemove
            }
        }

        if (offset != 0) {
            for (i in 0 until enterPosSize) {
                if (enterPos[i] >= start) {
                    enterPos[i] += offset
                }
            }
        }

        val newEnters = mutableListOf<Int>()
        for (i in start until start + count) {
            if (i < text.length && text[i] == '\n') {
                newEnters.add(i)
            }
        }
        if (newEnters.isNotEmpty()) {
            var index = java.util.Arrays.binarySearch(enterPos, 0, enterPosSize, start)
            if (index < 0) index = -index - 1

            val numNew = newEnters.size
            if (enterPosSize + numNew > enterPos.size) {
                enterPos = enterPos.copyOf((enterPosSize + numNew) * 2)
            }
            System.arraycopy(enterPos, index, enterPos, index + numNew, enterPosSize - index)
            for (i in newEnters.indices) {
                enterPos[index + i] = newEnters[i]
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

    companion object {
        private val PATTERN_LINE = Pattern.compile("(^.+$)+", Pattern.MULTILINE)
        private val PATTERN_TRAILING_WHITE_SPACE = Pattern.compile("[\\t ]+$", Pattern.MULTILINE)
    }
}
