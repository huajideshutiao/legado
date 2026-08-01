package io.legado.app.ui.compose.component.code

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TextFieldDefaults.indicatorLine
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppDecorationBox
import io.legado.app.ui.compose.component.AppFieldColors
import io.legado.app.ui.compose.component.AppTextFieldImpl
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** 后台着色前的等待, 对齐 CodeView 的 postDelayed(highlightRunnable, 150) */
private const val AsyncHighlightDelayMs = 150L

/** 查找匹配的背景色, 对齐 CodeView searchHighlightColor "#80FFFF00" (半透明黄) */
private val SearchMatchBackground = Color(0x80FFFF00)

/** 行号分隔线 alpha, 对齐 CodeView `lineNumberTextColor and 0x00FFFFFF or 0x60000000` */
private val LineDividerAlpha = 0x60 / 255f

/** 查找面板开着时编辑器文本变化的即时刷新上限, 超长文本等下次面板操作再算 (原版为增量更新) */
private const val SearchRefreshMaxLength = 20000

/**
 * 查找高亮状态 (屏幕级共享): 匹配区间由宿主屏幕的查找目标计算写入,
 * [CodeTextField] 的 VisualTransformation 消费并叠加渲染 (对齐原版 CodeView
 * 的 searchHighlightColor 全量黄底 + currentMatchColor 当前命中强调色)。
 */
class CodeSearchHighlightState {
    var keyword by mutableStateOf("")
    var useRegex by mutableStateOf(false)
    var matchCase by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var ranges by mutableStateOf(emptyList<IntRange>())
    var currentIndex by mutableIntStateOf(-1)
    var version by mutableIntStateOf(0)
        private set

    fun update(
        keyword: String,
        useRegex: Boolean,
        matchCase: Boolean,
        wholeWord: Boolean,
        ranges: List<IntRange>,
        currentIndex: Int,
    ) {
        this.keyword = keyword
        this.useRegex = useRegex
        this.matchCase = matchCase
        this.wholeWord = wholeWord
        this.ranges = ranges
        this.currentIndex = currentIndex
        version++
    }

    /** 编辑器文本变化时刷新匹配区间, 显示跟随 (对齐原版 updateSearchHighlightIncremental) */
    fun refresh(text: String) {
        if (keyword.isEmpty() || text.length > SearchRefreshMaxLength) return
        val ranges = buildSearchRanges(keyword, useRegex, matchCase, wholeWord, text)
        val index = if (ranges.isEmpty()) -1 else currentIndex.coerceIn(0, ranges.size - 1)
        if (ranges == this.ranges && index == currentIndex) return
        update(keyword, useRegex, matchCase, wholeWord, ranges, index)
    }

    fun clear() {
        keyword = ""
        useRegex = false
        matchCase = false
        wholeWord = false
        ranges = emptyList()
        currentIndex = -1
        version++
    }
}

/** 构造匹配区间 (对齐原版 getSearchPattern: 非正则走 quote, 全词加 \b, 忽略大小写走 flag) */
fun buildSearchRanges(
    keyword: String,
    useRegex: Boolean,
    matchCase: Boolean,
    wholeWord: Boolean,
    text: String,
): List<IntRange> {
    if (keyword.isEmpty()) return emptyList()
    var pattern = if (useRegex) keyword else Regex.escape(keyword)
    if (wholeWord) pattern = "\\b$pattern\\b"
    val regex = try {
        if (matchCase) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
    } catch (_: Exception) {
        return emptyList()
    }
    return regex.findAll(text).map { it.range }.toList()
}

/**
 * 语法高亮代码输入框 (KMP 共享), 替代 app 端 `ui/widget/code/CodeView` (EditText 子类)。
 *
 * 视觉对齐 [io.legado.app.ui.compose.component.AppTextField]: 透明容器 + 底部下划线
 * (未聚焦 controlNormal / 聚焦 accent), 差异仅在等宽字体与语法着色。
 * [showIndicator] = false 时去掉下划线, 用于对话框内嵌 (对齐原版 CodeDialog 无边框呈现)。
 *
 * @param syntax 着色规则集, 由调用方按字段类型传 (见 [rememberCodeSyntax])
 * @param searchHighlight 查找高亮叠加 (由外部屏幕持有, 传 null 不叠加), 对齐原版 CodeView
 *   查找替换的全量黄底 + 当前命中强调色; 只在聚焦字段上传非 null
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun CodeTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    syntax: CodeSyntaxScheme = CodeSyntaxScheme.None,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: String? = null,
    placeholder: String? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    showIndicator: Boolean = true,
    showLineNumbers: Boolean = false,
    minHeight: Dp = 56.dp,
    fontSize: TextUnit = 14.sp,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    searchHighlight: CodeSearchHighlightState? = null,
) {
    val transformation = rememberCodeHighlightTransformation(syntax, searchHighlight)
    AppTextFieldImpl(
        modifier = modifier,
        isError = isError,
        errorMessage = errorMessage,
    ) {
        val colors = AppFieldColors
        val baseStyle = LocalTextStyle.current.copy(
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
        )
        // 行号列与文本同处滚动容器, 需统一行高: 等宽字体对 CJK 回退字体的自然行高不一致,
        // 强制固定行高才能保证逐行对齐 (对齐原版 Canvas 逐行画号不受行高变化影响)。
        // 行号列只在文本含换行时出现 (对齐原版 enterPosSize > 0 条件), 单行字段走自然行高。
        val gutterShown = showLineNumbers && value.text.contains('\n')
        val codeStyle =
            if (gutterShown) baseStyle.copy(lineHeight = fontSize * 1.5f) else baseStyle
        val textColor = codeStyle.color.takeOrElse { colors.textColor(enabled).value }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (showIndicator) {
                        Modifier.indicatorLine(enabled, isError, interactionSource, colors)
                    } else {
                        Modifier
                    }
                )
                .defaultMinSize(minWidth = TextFieldDefaults.MinWidth, minHeight = minHeight),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = codeStyle.copy(color = textColor),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = false,
            maxLines = Int.MAX_VALUE,
            visualTransformation = transformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.cursorColor(isError).value),
            decorationBox = { innerTextField ->
                val decoration: @Composable () -> Unit = {
                    AppDecorationBox(
                        text = value.text,
                        innerTextField = innerTextField,
                        enabled = enabled,
                        singleLine = false,
                        // 装饰盒仅用于测量/占位, 着色已在 BasicTextField 内生效
                        visualTransformation = VisualTransformation.None,
                        interactionSource = interactionSource,
                        isError = isError,
                        label = label,
                        placeholder = placeholder,
                        leadingIcon = null,
                        trailingIcon = null,
                        colors = colors,
                    )
                }
                if (gutterShown) {
                    // 行号列与文本同处 decorationBox (BasicTextField 滚动容器) 内, 随文本同步滚动
                    val contentPadding = if (label == null) {
                        TextFieldDefaults.textFieldWithoutLabelPadding(
                            start = 0.dp,
                            end = 0.dp,
                            bottom = 4.dp
                        )
                    } else {
                        TextFieldDefaults.textFieldWithLabelPadding(
                            start = 0.dp,
                            end = 0.dp,
                            bottom = 4.dp
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        LineNumberGutter(
                            lineCount = value.text.count { it == '\n' } + 1,
                            textStyle = codeStyle,
                            topPadding = contentPadding.calculateTopPadding(),
                            bottomPadding = contentPadding.calculateBottomPadding(),
                        )
                        Box(Modifier.weight(1f)) { decoration() }
                    }
                } else {
                    decoration()
                }
            },
        )
    }
}

/**
 * 行号列, 布局数值对齐原版 CodeView.onDraw:
 * - 行号字号 = 文本字号 * 0.6f (lineNumberTextSize = textSize * 0.6f), 次要文字色右对齐
 * - 行号右边缘距文本 11dp (x = paddingLeft - 11f*density)
 * - 分隔线在行号右边缘右侧 5dp (lineX = paddingLeft - 6f*density), 1dp 宽,
 *   颜色 = 行号色 RGB + alpha 0x60
 * - 整列宽 = 行号宽 + 16dp (mLineNumberPadding = measureText + 16f*density), 文本从列右缘开始
 * 行高与文本强制一致 (调用方已统一 lineHeight), 随文本同步滚动。
 */
@Composable
private fun LineNumberGutter(
    lineCount: Int,
    textStyle: TextStyle,
    topPadding: Dp,
    bottomPadding: Dp,
) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .padding(start = 5.dp, top = topPadding, bottom = bottomPadding)
            .drawBehind {
                val dividerX = size.width - 6.dp.toPx()
                drawLine(
                    color = colors.secondaryText.copy(alpha = LineDividerAlpha),
                    start = Offset(dividerX, 0f),
                    end = Offset(dividerX, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        Text(
            text = buildLineNumbers(lineCount),
            color = colors.secondaryText,
            style = textStyle.copy(fontSize = textStyle.fontSize * 0.6f),
            textAlign = TextAlign.End,
            modifier = Modifier.padding(end = 11.dp),
        )
    }
}

private fun buildLineNumbers(lineCount: Int): String = buildString {
    for (i in 1..lineCount) {
        if (i > 1) append('\n')
        append(i)
    }
}

/** [CodeTextField] 的 String 重载: 无需保留选区/composition 的场景 */
@Composable
fun CodeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    syntax: CodeSyntaxScheme = CodeSyntaxScheme.None,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: String? = null,
    placeholder: String? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    showIndicator: Boolean = true,
    showLineNumbers: Boolean = false,
    minHeight: Dp = 56.dp,
    fontSize: TextUnit = 14.sp,
    searchHighlight: CodeSearchHighlightState? = null,
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    // 外部值被改写 (如撤销/重载) 时同步回本地状态
    if (fieldValue.text != value) fieldValue = fieldValue.copy(text = value)
    CodeTextField(
        value = fieldValue,
        onValueChange = {
            fieldValue = it
            onValueChange(it.text)
        },
        modifier = modifier,
        syntax = syntax,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        isError = isError,
        errorMessage = errorMessage,
        showIndicator = showIndicator,
        showLineNumbers = showLineNumbers,
        minHeight = minHeight,
        fontSize = fontSize,
        searchHighlight = searchHighlight,
    )
}

/**
 * 语法着色 [VisualTransformation] + 查找高亮叠加。
 *
 * VisualTransformation.filter 是同步 API, 无法 await 后台结果, 因此所有着色统一走
 * "先旧后新" (对齐原版 postDelayed(highlightRunnable, 150) 的延迟高亮):
 * 文本变化后先返回按变更偏移近似平移的旧 span, 后台 (Dispatchers.Default) 增量重算
 * 变更区间所在行, 完成后 bump version 触发重组拿到精确着色。
 * 查找高亮 (全量黄底 + 当前命中强调色) 由 [searchHighlight] 在着色结果上叠加。
 */
@Composable
private fun rememberCodeHighlightTransformation(
    syntax: CodeSyntaxScheme,
    search: CodeSearchHighlightState?,
): VisualTransformation {
    if (syntax.rules.isEmpty() && search == null) return VisualTransformation.None
    val scope = rememberCoroutineScope()
    val cache = remember(syntax) { CodeHighlightCache(syntax.rules) }
    val version = cache.version
    val searchVersion = search?.version
    val accent = AppTheme.colors.accent
    return remember(cache, version, search, searchVersion, accent) {
        VisualTransformation { text ->
            val base = cache.highlight(text.text, scope)
            val searchOverlay =
                search?.takeIf { it.keyword.isNotEmpty() && it.ranges.isNotEmpty() }
            val result = if (searchOverlay == null) {
                base
            } else {
                buildAnnotatedString {
                    append(base)
                    val len = text.text.length
                    searchOverlay.ranges.forEachIndexed { i, range ->
                        val s = range.first.coerceAtMost(len)
                        val e = range.last.coerceAtMost(len)
                        if (s < e) {
                            addStyle(
                                SpanStyle(
                                    background = if (i == searchOverlay.currentIndex) {
                                        accent
                                    } else {
                                        SearchMatchBackground
                                    }
                                ),
                                s,
                                e,
                            )
                        }
                    }
                }
            }
            TransformedText(result, OffsetMapping.Identity)
        }
    }
}

private class CodeHighlightCache(private val rules: List<CodeSyntaxRule>) {

    /** 后台着色完成后自增, 供 Compose 侧重建 VisualTransformation 触发重绘 */
    var version by mutableIntStateOf(0)
        private set

    private var cachedText: String? = null
    private var cached: AnnotatedString? = null
    private var spans = ArrayList<CodeSpan>()
    private var pendingText: String? = null
    private var job: Job? = null

    fun highlight(text: String, scope: CoroutineScope): AnnotatedString {
        if (cachedText == text) return cached!!
        if (pendingText != text) {
            pendingText = text
            job?.cancel()
            job = scope.launch {
                // 首次加载不延迟 (对齐原版 setText → reHighlightSyntax 立即后台全量)
                delay(if (cachedText == null) 0 else AsyncHighlightDelayMs)
                // 后台只读计算, 字段统一在主线程落盘, 避免并发 compute 写坏增量基线
                val result = withContext(Dispatchers.Default) { compute(text) }
                cached = result.first
                spans = result.second
                cachedText = text
                pendingText = null
                version++
            }
        }
        return staleOrPlain(text)
    }

    private fun compute(text: String): Pair<AnnotatedString, ArrayList<CodeSpan>> {
        val oldText = cachedText
        val newSpans = if (oldText == null) {
            matchCodeSpans(text, rules)
        } else {
            incremental(oldText, text)
        }
        return buildAnnotated(text, newSpans) to newSpans
    }

    /**
     * 增量着色: 与旧文本 diff 出变更区间, 只重算该区间所在行的匹配
     * (对齐原版 highlightSyntaxIncremental 的 dirty 行重算), 避免长文本每次按键全量正则。
     */
    private fun incremental(oldText: String, newText: String): ArrayList<CodeSpan> {
        val minLen = minOf(oldText.length, newText.length)
        var prefix = 0
        while (prefix < minLen && oldText[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < minLen - prefix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) suffix++
        val offset = newText.length - oldText.length

        // 1. 变更点之后的 span 整体平移, 跨变更点的 span 只拉伸/收缩 end (对齐原版 onTextChanged)
        val shifted = ArrayList<CodeSpan>(spans.size + 8)
        for (span in spans) {
            if (span.start >= prefix) {
                val s = max(prefix, span.start + offset)
                val e = max(prefix, span.end + offset)
                if (s < e) shifted.add(CodeSpan(s, e, span.color))
            } else {
                if (span.end <= prefix) {
                    shifted.add(span)
                } else {
                    val e = max(prefix, span.end + offset)
                    if (e > span.start) shifted.add(CodeSpan(span.start, e, span.color))
                }
            }
        }

        // 2. 变更区间所在行范围 (对齐原版 lineStart/lineEnd 计算)
        val dEnd = prefix + (newText.length - suffix - prefix)
        val lineStart = newText.lastIndexOf('\n', prefix - 1) + 1
        val lineEndRaw = newText.indexOf('\n', dEnd)
        val lineEnd = if (lineEndRaw == -1) newText.length else lineEndRaw

        // 3. 移除区间内旧 span, 重算区间并合并 (对齐原版 removeAll + addAll + sort)
        val kept = ArrayList<CodeSpan>(shifted.size + 8)
        for (span in shifted) {
            if (span.start >= lineStart && span.end <= lineEnd) continue
            kept.add(span)
        }
        for (span in matchCodeSpans(newText.substring(lineStart, lineEnd), rules)) {
            kept.add(CodeSpan(span.start + lineStart, span.end + lineStart, span.color))
        }
        kept.sortWith(compareBy({ it.start }, { -it.end }))
        return kept
    }

    private fun buildAnnotated(text: String, spans: List<CodeSpan>): AnnotatedString {
        if (spans.isEmpty()) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            for (span in spans) {
                val s = span.start.coerceIn(0, text.length)
                val e = span.end.coerceIn(0, text.length)
                if (s < e) addStyle(SpanStyle(color = span.color), s, e)
            }
        }
    }

    /**
     * 防抖窗口内沿用旧 span, 按变更偏移近似平移 (对齐原版: span 挂在 Editable 上随文本
     * 自动偏移, 新增字符无色), 避免长文本每次按键闪回无色。
     */
    private fun staleOrPlain(text: String): AnnotatedString {
        val old = cachedText ?: return AnnotatedString(text)
        val stale = cached ?: return AnnotatedString(text)
        if (stale.spanStyles.isEmpty()) return AnnotatedString(text)
        val minLen = minOf(old.length, text.length)
        var prefix = 0
        while (prefix < minLen && old[prefix] == text[prefix]) prefix++
        val offset = text.length - old.length
        return buildAnnotatedString {
            append(text)
            for (range in stale.spanStyles) {
                if (range.start >= text.length) continue
                var s = range.start
                var e = range.end
                if (s >= prefix) {
                    s = max(prefix, s + offset)
                    e = max(prefix, e + offset)
                } else if (e > prefix) {
                    e = max(prefix, e + offset)
                }
                s = s.coerceIn(0, text.length)
                e = e.coerceIn(0, text.length)
                if (s < e) addStyle(range.item, s, e)
            }
        }
    }
}
