package io.legado.app.ui.widget.code

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.widget.text.ScrollMultiAutoCompleteTextView
import java.util.SortedMap
import java.util.TreeMap
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.roundToInt

@Suppress("unused")
class CodeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ScrollMultiAutoCompleteTextView(context, attrs) {

    private var tabWidth = 0
    private var tabWidthInCharacters = 0
    var updateDelayTime = 500
    private var modified = true
    private var highlightWhileTextChanging = true
    private var hasErrors = false
    private var mRemoveErrorsWhenTextChanged = true
    private val mUpdateHandler = Handler(Looper.getMainLooper())
    private var mAutoCompleteTokenizer: Tokenizer? = null
    private val displayDensity = resources.displayMetrics.density
    private val mErrorHashSet: SortedMap<Int, Int> = TreeMap()
    private val mSyntaxPatternMap: MutableMap<Pattern, Int> = HashMap()
    private var mIndentCharacterList = mutableSetOf('{', '+', '-', '*', '/', '=')
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

    private val enterPos = mutableListOf<Int>()

    // 查找替换相关
    private var searchKeyword: String = ""
    private var useRegex: Boolean = false
    private var matchCase: Boolean = false
    private var matchWholeWord: Boolean = false
    private var matchRanges = mutableListOf<Pair<Int, Int>>()
    private var currentMatchIndex = -1
    var onSearchReplaceAction: ((String) -> Unit)? = null

    // 查找替换背景色
    private val searchHighlightColor = "#80FFFF00".toColorInt() // 半透明黄
    private val currentMatchColor = "#800080FF".toColorInt() // 半透明蓝

    private var isHighlighting = false

    private val mUpdateRunnable = Runnable {
        val source = text
        if (source is Editable) {
            highlightWithoutChange(source)
        }
    }

    private val mEditorTextWatcher: TextWatcher = object : TextWatcher {
        private var start = 0
        private var count = 0
        override fun beforeTextChanged(
            charSequence: CharSequence, start: Int, before: Int, count: Int
        ) {
            this.start = start
            this.count = count
        }

        override fun onTextChanged(
            charSequence: CharSequence, start: Int, before: Int, count: Int
        ) {
            if (!modified || isHighlighting) return
            if (highlightWhileTextChanging) {
                if (mSyntaxPatternMap.isNotEmpty()) {
                    cancelHighlighterRender()
                    convertTabs(editableText, start, count)
                    mUpdateHandler.postDelayed(mUpdateRunnable, updateDelayTime.toLong())
                }
            }
            if (mRemoveErrorsWhenTextChanged) removeAllErrorLines()
        }

        override fun afterTextChanged(editable: Editable) {
            if (isHighlighting) return
            if (!highlightWhileTextChanging) {
                if (!modified) return
                cancelHighlighterRender()
                if (mSyntaxPatternMap.isNotEmpty()) {
                    convertTabs(editableText, start, count)
                    mUpdateHandler.postDelayed(mUpdateRunnable, updateDelayTime.toLong())
                }
            }
            if (isLineNumberEnabled) {
                getEnterPos(editable)
                updateLineNumberPadding()
            }
            if (searchKeyword.isNotEmpty()) {
                recomputeSearchMatches()
            }
        }
    }

    init {
        if (mAutoCompleteTokenizer == null) {
            mAutoCompleteTokenizer = KeywordTokenizer()
        }
        setTokenizer(mAutoCompleteTokenizer)

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
                if (modified && end - start == 1 && start < source.length && dStart < dest.length) {
                    val c = source[start]
                    if (c == '\n') {
                        return@InputFilter autoIndent(source, dest, dStart, dEnd)
                    }
                }
                source
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
        if (currentMatchIndex < 0) return
        val range = matchRanges.getOrNull(currentMatchIndex) ?: return
        val editable = editableText
        if (range.first >= 0 && range.second <= editable.length && range.first < range.second) {
            val spans =
                editable.getSpans(range.first, range.second, BackgroundColorSpan::class.java)
            for (span in spans) {
                editable.removeSpan(span)
            }
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
            val c = dest[indentEnd]
            if (c != ' ' && c != '\t') break
            indentEnd++
        }
        val indentStr = dest.subSequence(lineStart, indentEnd)
        val indent = StringBuilder(source).append(indentStr)
        // 如果上一行以特定字符结尾
        lastNonSpaceChar?.let { char ->
            if (mIndentCharacterList.contains(char)) {
                indent.append("  ")
                // 如果后面跟着的是匹配的闭合符号，则多加一个换行并缩进
                mClosePairMap[char]?.let { closeChar ->
                    if (dEnd < dest.length && dest[dEnd] == closeChar) {
                        indent.append("\n").append(indentStr)
                        post {
                            setSelection(dStart + indent.length - indentStr.length - 1)
                        }
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

    private fun highlight(editable: Editable): Editable {
        // if (editable.isEmpty() || editable.length > 1024) return editable
        if (editable.length !in 1..1024) {
            try {
                clearSpans(editable)
                highlightSearch(editable)
            } catch (e: Exception) {
            }
            return editable
        }
        try {
            clearSpans(editable)
            highlightErrorLines(editable)
            highlightSyntax(editable)
            highlightSearch(editable)
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        }
        return editable
    }

    private fun highlightWithoutChange(editable: Editable) {
        if (isHighlighting) return
        isHighlighting = true
        modified = false
        highlight(editable)
        modified = true
        isHighlighting = false
    }

    fun setTextHighlighted(text: CharSequence?) {
        if (text.isNullOrEmpty()) return
        cancelHighlighterRender()
        removeAllErrorLines()
        modified = false
        setText(highlight(SpannableStringBuilder(text)))
        modified = true
    }

    fun setTabWidth(characters: Int) {
        if (tabWidthInCharacters == characters) return
        tabWidthInCharacters = characters
        tabWidth = (paint.measureText("m") * characters).roundToInt()
    }

    private fun clearSpans(editable: Editable) {
        editable.getSpans(0, editable.length, ForegroundColorSpan::class.java).forEach {
            editable.removeSpan(it)
        }
        editable.getSpans(0, editable.length, BackgroundColorSpan::class.java).forEach {
            editable.removeSpan(it)
        }
    }

    fun cancelHighlighterRender() {
        mUpdateHandler.removeCallbacks(mUpdateRunnable)
    }

    private fun convertTabs(editable: Editable, start: Int, count: Int) {
        var startIndex = start
        if (tabWidth < 1) return
        val s = editable.toString()
        val stop = startIndex + count
        while (s.indexOf("\t", startIndex).also { startIndex = it } > -1 && startIndex < stop) {
            editable.setSpan(
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

    fun setAutoCompleteTokenizer(tokenizer: Tokenizer?) {
        mAutoCompleteTokenizer = tokenizer
    }

    fun setRemoveErrorsWhenTextChanged(removeErrors: Boolean) {
        mRemoveErrorsWhenTextChanged = removeErrors
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

    /**
     * 在文本中查找指定关键词，支持正则表达式、大小写匹配、全词匹配等功能
     *
     * @param keyword 要查找的关键词
     * @param regex 是否使用正则表达式匹配
     * @param matchCase 是否区分大小写
     * @param matchWholeWord 是否匹配整个单词
     * @param forward 是否向下查找（true为向下，false为向上）
     * @param force 是否强制重新计算匹配结果
     * @param scrollToMatch 是否滚动到匹配位置
     */
    fun find(
        keyword: String,
        regex: Boolean,
        matchCase: Boolean,
        matchWholeWord: Boolean,
        forward: Boolean = false,
        force: Boolean = false,
        scrollToMatch: Boolean = false
    ) {
        // 获取文本内容
        val textStr = text.toString()
        // 如果关键词或文本为空，清除搜索结果并返回
        if (keyword.isEmpty() || textStr.isEmpty()) {
            clearSearch()
            return
        }

        // 判断是否需要重新计算匹配结果
        val needRecompute =
            force || this.searchKeyword != keyword || this.useRegex != regex || this.matchCase != matchCase || this.matchWholeWord != matchWholeWord || matchRanges.isEmpty()

        // 更新搜索参数
        this.searchKeyword = keyword
        this.useRegex = regex
        this.matchCase = matchCase
        this.matchWholeWord = matchWholeWord

        // 如果需要重新计算匹配结果
        if (needRecompute) {
            matchRanges.clear()
            currentMatchIndex = -1
            try {
                // 设置匹配标志
                val flags = if (matchCase) 0 else Pattern.CASE_INSENSITIVE
                var patternStr = keyword
                // 如果不是正则表达式，转义特殊字符
                if (!regex) {
                    patternStr = Pattern.quote(keyword)
                }
                // 如果需要全词匹配，添加边界匹配
                if (matchWholeWord) {
                    patternStr = "\\b$patternStr\\b"
                }
                // 编译正则表达式模式
                val pattern = Pattern.compile(patternStr, flags)
                val matcher = pattern.matcher(textStr)
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
            val spans =
                editable.getSpans(newRange.first, newRange.second, BackgroundColorSpan::class.java)
            for (span in spans) {
                editable.removeSpan(span)
            }
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
        val prevRange = matchRanges.getOrNull(prevIndex)
        val newRange = matchRanges.getOrNull(newIndex)

        prevRange?.let { range ->
            if (range.first >= 0 && range.second <= editable.length && range.first < range.second) {
                val spans =
                    editable.getSpans(range.first, range.second, BackgroundColorSpan::class.java)
                for (span in spans) {
                    editable.removeSpan(span)
                }
                editable.setSpan(
                    BackgroundColorSpan(searchHighlightColor),
                    range.first,
                    range.second,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        newRange?.let { range ->
            if (range.first >= 0 && range.second <= editable.length && range.first < range.second) {
                val spans =
                    editable.getSpans(range.first, range.second, BackgroundColorSpan::class.java)
                for (span in spans) {
                    editable.removeSpan(span)
                }
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
        }

        if (currentMatchIndex in matchRanges.indices) {
            val range = matchRanges[currentMatchIndex]
            val editable = editableText
            if (range.first >= 0 && range.second <= editable.length) {
                // 执行替换（replaceText 为空时即为删除）
                editable.replace(range.first, range.second, replaceText)
                // 替换后文本发生变化，所有后续匹配的 Offset 均已失效，必须强制重新搜索
                find(
                    searchKeyword, useRegex, matchCase, matchWholeWord, true, true,true
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
            find(keyword, regex, matchCase, matchWholeWord, true)
        }

        if (matchRanges.isEmpty()) return
        val editable = editableText
        // 从后往前替换，避免索引失效
        for (i in matchRanges.indices.reversed()) {
            val range = matchRanges[i]
            if (range.first >= 0 && range.second <= editable.length) {
                editable.replace(range.first, range.second, replaceText)
            }
        }
        clearSearch()
    }

    fun clearSearch() {
        searchKeyword = ""
        matchRanges.clear()
        currentMatchIndex = -1
        reHighlightSearch()
    }

    private fun recomputeSearchMatches() {
        if (searchKeyword.isEmpty()) return
        val textStr = text.toString()
        matchRanges.clear()
        currentMatchIndex = -1
        try {
            val flags = if (matchCase) 0 else Pattern.CASE_INSENSITIVE
            var patternStr = searchKeyword
            if (!useRegex) {
                patternStr = Pattern.quote(searchKeyword)
            }
            if (matchWholeWord) {
                patternStr = "\\b$patternStr\\b"
            }
            val pattern = Pattern.compile(patternStr, flags)
            val matcher = pattern.matcher(textStr)
            while (matcher.find()) {
                matchRanges.add(Pair(matcher.start(), matcher.end()))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        reHighlightSearch()
    }

    private fun reHighlightSearch() {
        mUpdateHandler.removeCallbacks(mUpdateRunnable)
        mUpdateHandler.post(mUpdateRunnable)
    }

    // ----------------------

    fun setHighlightWhileTextChanging(updateWhileTextChanging: Boolean) {
        highlightWhileTextChanging = updateWhileTextChanging
    }

    private fun updateLineNumberPadding() {
        if (defaultPaddingLeft == null) defaultPaddingLeft = paddingLeft
        mLineNumberPadding = if (isLineNumberEnabled && enterPos.isNotEmpty()) {
            (mLineNumberPaint.measureText((enterPos.size + 1).toString()) + 16f * displayDensity).toInt()
        } else {
            defaultPaddingLeft!!
        }
        if (mLineNumberPadding != paddingLeft) setPadding(
            mLineNumberPadding, paddingTop, paddingRight, paddingBottom
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isLineNumberEnabled && enterPos.isNotEmpty()) {
            val firstLine = layout.getLineForOffset(scrollY)
            val lastLine = layout.getLineForOffset(scrollY + height)
            var lineStartOffset: Int
            var prevLineNumber = -1
            for (i in firstLine..lastLine) {
                lineStartOffset = layout.getLineStart(i)
                if (lineStartOffset == 0 || text[lineStartOffset - 1] == '\n') {
                    var lineNumber = enterPos.binarySearch(lineStartOffset)
                    if (lineNumber < 0) lineNumber = -lineNumber - 1
                    if (lineNumber != prevLineNumber) {
                        val x = paddingLeft - 11f * displayDensity
                        val y = layout.getLineBaseline(i).toFloat() + paddingTop
                        canvas.drawText((lineNumber + 1).toString(), x, y, mLineNumberPaint)
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
        enterPos.clear()
        for (i in text.indices) {
            if (text[i] == '\n') {
                enterPos.add(i)
            }
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