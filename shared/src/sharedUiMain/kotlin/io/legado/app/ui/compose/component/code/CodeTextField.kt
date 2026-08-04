package io.legado.app.ui.compose.component.code

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TextFieldDefaults.indicatorLine
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
import kotlin.math.roundToInt

/** 后台着色前的等待, 对齐 CodeView 的 postDelayed(highlightRunnable, 150) */
private const val AsyncHighlightDelayMs = 150L

/** 查找匹配的背景色, 对齐 CodeView searchHighlightColor "#80FFFF00" (半透明黄) */
private val SearchMatchBackground = Color(0x80FFFF00)

/** 行号分隔线 alpha, 对齐 CodeView `lineNumberTextColor and 0x00FFFFFF or 0x60000000` */
private val LineDividerAlpha = 0x60 / 255f

/** 下划线形态水平内边距 4dp (对齐 AppTextField 与周边 4dp 布局; M2 filled 默认 16dp 是容器形态所需) */
private val TextFieldHorizontalPadding = 4.dp

/** 无 label 时文本顶部留白 (M2 textFieldWithoutLabelPadding 默认 top = TextFieldPadding = 16dp) */
private val TextFieldTopPadding = 16.dp

/** 有 label 时 label 基线距组件顶 (M2 FirstBaselineOffset = 20dp) */
private val TextFieldFirstBaselineOffset = 20.dp

/** label 基线到文本顶的间距 (M2 内部 TextFieldTopPadding = 2dp, label 浮动后文本顶 = 基线 + 2dp) */
private val TextFieldLabelToText = 2.dp

/**
 * 文本底到指示线的间距 4dp (原版 EditText wrap_content 底部 inset 约 2-4dp)。
 * 本组件顶对齐 (singleLine=false), 内容高度 = 顶留白 + 行高 + bottom, 因此 bottom 即"文本-下划线"距离。
 */
private val TextFieldBottomInset = 4.dp

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
 * 语法高亮代码输入框 (KMP 次一级实现)。
 *
 * Android 仍保留 `ui/widget/code/CodeView` 作为 View 专项编辑器，提供自动补全、滚动窗口
 * 高亮、原生撤销/重做和 ActionMode 集成；本组件供共享界面及非 Android 平台使用。
 *
 * MD2 纯下划线风格 (对照 MD2 TextField): 无填充底、无圆角盒、无边框, 仅底部下划线
 * indicatorLine (未聚焦 controlNormal / 聚焦 accent / 错误 error) + hint (label/placeholder)
 * + 4dp 水平边距 (下划线形态收窄, 对齐 AppTextField; M2 filled 默认 16dp 是容器形态所需),
 * 垂直按内容包裹: 文本底距指示线恒 4dp (对齐原版 EditText wrap_content 底部 inset 2-4dp),
 * 字体跟随主题默认, 差异仅在语法着色。
 * [showIndicator] = false 时连下划线也不画, 用于对话框内嵌 (对齐原版 CodeDialog 无边框呈现)。
 * [autoComplete] 轻量自动补全 (对齐原版 CodeView 的 AutoCompleteAdapter 词表 + fuzzy 匹配,
 * 弹层锚定光标, 默认开启; 弹层弹出时支持键盘: 上下移动高亮、回车/Tab 确认、ESC 收起)。
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
    fontSize: TextUnit = 14.sp,
    // 默认最小高度 = 顶留白 + 固定行高 + 底部 4dp, 单行字段高度贴合内容 (消除 minHeight 死区)
    minHeight: Dp = codeFieldDefaultMinHeight(label != null, fontSize),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    searchHighlight: CodeSearchHighlightState? = null,
    autoComplete: Boolean = true,
) {
    val transformation = rememberCodeHighlightTransformation(syntax, searchHighlight)
    val colors = AppFieldColors
    val themeColors = AppTheme.colors
    // 字体跟随主题默认 (原版 CodeView 未设置等宽字体, 用系统默认字体)
    val baseStyle = LocalTextStyle.current.copy(fontSize = fontSize)
    // 行号列与文本同处滚动容器, 需统一行高: 默认字体下 CJK 回退与拉丁字符的自然行高不一致,
    // 强制固定行高才能保证逐行对齐 (对齐原版 Canvas 逐行画号不受行高变化影响)。
    // 行号列只在文本含换行时出现 (对齐原版 enterPosSize > 0 条件)。
    // 行号计数增量跟踪: 常规编辑在 onValueChange 里按前后文 diff 求新行数 (O(编辑距离)),
    // 外部整体改写 (撤销/重载/查找替换等不经本字段 onValueChange 的写入) 走组合期全量重算,
    // 避免每次按键 O(n) 扫描全文。
    var lastLineCountText by remember { mutableStateOf(value.text) }
    var lineCount by remember { mutableIntStateOf(countLineNumbers(value.text)) }
    if (lastLineCountText !== value.text) {
        lastLineCountText = value.text
        lineCount = countLineNumbers(value.text)
    }
    // 行数增量 + 转发: 文本实例相同时只更新增量, 行号串 (remember(lineCount)) 不重建
    val trackedValueChange: (TextFieldValue) -> Unit = { newValue ->
        val newText = newValue.text
        if (newText !== lastLineCountText) {
            lineCount += newlineDelta(lastLineCountText, newText)
            lastLineCountText = newText
        }
        onValueChange(newValue)
    }
    val gutterShown = showLineNumbers && lineCount > 1
    // 固定行高 fontSize*1.5, 单行/多行一致: 单行与多行的垂直几何统一 (行高恒定, 高度只随行数增长,
    // 回车换行时行距不跳变); 也是内容推导 minHeight 与行号逐行对齐的前提
    val codeStyle = baseStyle.copy(lineHeight = fontSize * 1.5f)
    val textColor = codeStyle.color.takeOrElse { colors.textColor(enabled).value }
    // MD2 纯下划线: 水平起点 4dp (下划线形态收窄, 对齐 AppTextField; showIndicator=false 内嵌时 0dp);
    // 垂直: top 走 M2 默认 (无 label 16dp / label 基线 20dp), bottom 固定 4dp —— 本组件顶对齐
    // (singleLine=false), bottom 即"文本底到指示线"距离 (原版 EditText wrap_content 底部 inset 2-4dp)
    val horizontalPadding = if (showIndicator) TextFieldHorizontalPadding else 0.dp
    val contentPadding = if (label == null) {
        TextFieldDefaults.textFieldWithoutLabelPadding(
            start = horizontalPadding,
            end = horizontalPadding,
            bottom = TextFieldBottomInset
        )
    } else {
        TextFieldDefaults.textFieldWithLabelPadding(
            start = horizontalPadding,
            end = horizontalPadding,
            bottom = TextFieldBottomInset
        )
    }
    // 轻量自动补全: 聚焦且光标处 token 非空时, 按词表 + 行内词 fuzzy 匹配出候选
    // (对齐原版 AutoCompleteAdapter/performFiltering; 行号可不上, 自动补全保留)
    val isFocused by interactionSource.collectIsFocusedAsState()
    val autoMatches = remember(value.text, value.selection, isFocused, autoComplete, readOnly) {
        if (!autoComplete || !isFocused || readOnly || !value.selection.collapsed) {
            emptyList()
        } else {
            val text = value.text
            val cursor = value.selection.start
            val tokenStart = findTokenStart(text, cursor)
            val token = text.substring(tokenStart, cursor)
            if (token.isEmpty() || shouldSuppressCompletion(text, cursor)) {
                emptyList()
            } else {
                val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
                var lineEnd = text.indexOf('\n', cursor)
                if (lineEnd == -1) lineEnd = text.length
                findCodeCompletions(token, text.substring(lineStart, lineEnd))
            }
        }
    }
    // 键盘导航状态 (对齐原版 AutoCompleteTextView 的 listSelection): -1 = 未选中, 回车取第 0 项;
    // autoDismissedText: ESC/确认后当前文本不再弹候选, 文本变化后恢复 (对齐原版 dismissDropDown)
    var autoSelectedIndex by remember { mutableIntStateOf(-1) }
    var autoDismissedText by remember { mutableStateOf<String?>(null) }
    val matches = if (autoDismissedText == value.text) emptyList() else autoMatches
    LaunchedEffect(matches) { autoSelectedIndex = -1 }
    // 确认候选 (键盘回车/Tab 与点击共用), 对齐原版 replaceText 后 dismissDropDown
    val applyMatch: (Int) -> Unit = { index ->
        val (newText, newSelection) = applyCompletion(value.text, value.selection, matches[index])
        autoDismissedText = newText
        autoSelectedIndex = -1
        onValueChange(value.copy(text = newText, selection = newSelection))
    }
    // 键盘事件: onPreviewKeyEvent 挂在字段外层 Box (BasicTextField 的祖先), 预览阶段先于字段
    // 内部 keyInput, 弹层 focusable=false 不抢焦点, 按键由字段统一接收 (原版 popup 亦如此)。
    // 候选弹出时 上下/回车/Tab 优先走补全, ESC 收起; 无候选时回车交还字段 (换行缩进走
    // CodeEditorState.adjustInput), Tab 插入 "\t" (对齐原版 EditText 硬件 Tab 行为)。
    val previewKeyHandler: (KeyEvent) -> Boolean = { event ->
        if (!autoComplete || readOnly || !isFocused) {
            false
        } else if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            when (event.key) {
                Key.DirectionDown -> if (matches.isEmpty()) false else {
                    autoSelectedIndex = (autoSelectedIndex + 1).coerceAtMost(matches.lastIndex)
                    true
                }

                Key.DirectionUp -> if (matches.isEmpty()) false else {
                    autoSelectedIndex = (autoSelectedIndex - 1).coerceAtLeast(-1)
                    true
                }

                Key.Enter -> if (matches.isEmpty()) false else {
                    applyMatch(if (autoSelectedIndex == -1) 0 else autoSelectedIndex)
                    true
                }

                Key.Tab -> if (event.isShiftPressed || matches.isEmpty()) {
                    // Shift+Tab 不参与补全 (保留焦点后退语义); 无候选时插入 "\t"
                    if (matches.isEmpty() && !event.isShiftPressed) {
                        onValueChange(insertTabAtSelection(value))
                        true
                    } else {
                        false
                    }
                } else {
                    applyMatch(if (autoSelectedIndex == -1) 0 else autoSelectedIndex)
                    true
                }

                Key.Escape -> if (matches.isEmpty()) false else {
                    autoDismissedText = value.text
                    true
                }

                else -> false
            }
        }
    }
    // 弹层锚点: 光标所在行 Y + X; X 用 TextMeasurer 实测行首到光标的文本宽度与行号列宽
    // (替代原 0.6em/1em 近似估算, 对齐原版 showDropDown 的 layout.getPrimaryHorizontal),
    // 测量失败/超长回退估算
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 8)
    // 行号列宽 (px, 未显示行号时 null): 5dp 左距 + 号宽 + 11dp 右距 (对齐原版
    // mLineNumberPadding = measureText + 16f*density), 分隔线与补全弹层共用同一实测值
    val gutterTextStyle = codeStyle.copy(fontSize = codeStyle.fontSize * 0.6f)
    val gutterWidthPx: Float? = if (gutterShown) {
        measureTextWidth(
            textMeasurer,
            lineCount.toString(),
            gutterTextStyle,
            density
        )?.let { numberWidth ->
            with(density) { (5.dp + 11.dp).toPx() + numberWidth }
        }
    } else null
    // 行号与正文基线对齐: 两种字号在同一固定行高下由 Compose 各自垂直居中, 基线差 =
    // 各自 firstBaseline 之差 (Roboto 14sp/8.4sp 下约 1.9dp, 即"行号比正文基线偏高"的错位)。
    // 实测消除, 不依赖字体度量假设
    val gutterBaselineShift = if (gutterShown) {
        with(density) {
            (measureFirstBaseline(textMeasurer, codeStyle, density) -
                measureFirstBaseline(textMeasurer, gutterTextStyle, density)).toDp()
        }
    } else {
        0.dp
    }
    val popupOffset = if (matches.isEmpty()) {
        IntOffset.Zero
    } else {
        val text = value.text
        val cursor = value.selection.start
        val cursorLine = text.take(cursor).count { it == '\n' }
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val lineHeight = codeStyle.lineHeight.takeOrElse { fontSize * 1.5f }
        val y = with(density) {
            // label 场景文本顶 = contentPadding.top (label 基线 20dp) + 2dp (对齐 M2 放置公式)
            (contentPadding.calculateTopPadding() +
                if (label != null) TextFieldLabelToText else 0.dp).toPx() +
                cursorLine * lineHeight.toPx()
        }.roundToInt()
        var x = with(density) {
            contentPadding.calculateStartPadding(LayoutDirection.Ltr).toPx()
        }.roundToInt()
        // 行号列宽: gutterShown 时文本起点右移 (列宽实测值, 与分隔线同源)
        if (gutterWidthPx != null) {
            x += gutterWidthPx.roundToInt()
        }
        // 行首到光标宽度: 实测优先, 失败回退 0.6em/1em 估算
        val prefixWidth = measureTextWidth(
            textMeasurer,
            text.substring(lineStart, cursor),
            codeStyle,
            density,
        )
        if (prefixWidth != null) {
            x += prefixWidth.roundToInt()
        } else {
            val charWidthPx = with(density) { (fontSize * 0.6f).toPx() }.roundToInt()
            val wideWidthPx = with(density) { fontSize.toPx() }.roundToInt()
            for (i in lineStart until cursor) {
                x += if (isFullWidthChar(text[i])) wideWidthPx else charWidthPx
            }
        }
        IntOffset(x, y)
    }
    AppTextFieldImpl(
        modifier = modifier,
        isError = isError,
        errorMessage = errorMessage,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent(previewKeyHandler)
        ) {
            BasicTextField(
                value = value,
                onValueChange = trackedValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (showIndicator) {
                            // MD2 纯下划线: 仅底部 indicatorLine, 无填充底/圆角盒/边框
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
                    // 行号列包在 innerTextField 外层: 行号只在外部无界高度 + 滚动容器场景启用,
                    // 此时字段整体随容器滚动, 行号与文本同步; 若字段自身有界 (内部滚动), decoration
                    // 内容不随文本滚动, 行号会钉在顶部 (当前无线号使用场景, 保持现状)。
                    // label/placeholder 由
                    // AppDecorationBox 全宽渲染 (hint 从 contentPadding 起点 4dp 开始, 不被
                    // CodeView gutter 挤开; LineNumberGutter 的 5dp/11dp/6dp 几何与
                    // CodeView.onDraw 的 paddingLeft-11dp / paddingLeft-6dp 对齐)
                    val fieldContent: @Composable () -> Unit = {
                        if (gutterShown) {
                            // 分隔线画在 Row 上, 覆盖整个文本区高度: 行号列 Box 只与行号等高,
                            // 长行软换行时文本区比行号列高, 画在行号列上会提前截断
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .drawBehind {
                                        gutterWidthPx?.let { gW ->
                                            // 对齐原版 lineX = paddingLeft - 6f*density
                                            val dividerX = gW - 6.dp.toPx()
                                            drawLine(
                                                color = themeColors.secondaryText.copy(alpha = LineDividerAlpha),
                                                start = Offset(dividerX, 0f),
                                                end = Offset(dividerX, size.height),
                                                strokeWidth = 1.dp.toPx(),
                                            )
                                        }
                                    }
                            ) {
                                LineNumberGutter(
                                    lineCount = lineCount,
                                    textStyle = codeStyle,
                                    baselineShift = gutterBaselineShift,
                                )
                                Box(Modifier.weight(1f)) { innerTextField() }
                            }
                        } else {
                            innerTextField()
                        }
                    }
                    AppDecorationBox(
                        text = value.text,
                        innerTextField = fieldContent,
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
                        contentPadding = contentPadding,
                    )
                },
            )
            if (matches.isNotEmpty()) {
                AutoCompletePopup(
                    matches = matches,
                    selectedIndex = autoSelectedIndex,
                    offset = popupOffset,
                    onSelect = applyMatch,
                )
            }
        }
    }
}

/**
 * 行号列, 布局数值对齐原版 CodeView.onDraw:
 * - 行号字号 = 文本字号 * 0.6f (lineNumberTextSize = textSize * 0.6f), 次要文字色右对齐
 * - 行号右边缘距文本 11dp (x = paddingLeft - 11f*density)
 * - 分隔线在行号右边缘右侧 5dp (lineX = paddingLeft - 6f*density), 1dp 宽,
 *   颜色 = 行号色 RGB + alpha 0x60; 线画在承载 Row 上 (见调用处), 覆盖整个文本区高度
 * - 整列宽 = 行号宽 + 16dp (mLineNumberPadding = measureText + 16f*density), 文本从列右缘开始
 * [baselineShift]: 行号字号小于正文, 同一固定行高下 Compose 各自垂直居中导致行号基线偏高
 * (约 1.9dp), 传入实测基线差把行号下移与正文行基线对齐。
 * 行高与文本一致 (调用方已统一 lineHeight), 随文本同步滚动。
 */
@Composable
private fun LineNumberGutter(
    lineCount: Int,
    textStyle: TextStyle,
    baselineShift: Dp,
) {
    val colors = AppTheme.colors
    // 行号串只在行数变化时重建: 常规按键 (行数不变) 复用同一实例, BasicText 节点按内容语义比较
    // 跳过重排; 滚动只改放置 (verticalScroll 不重组子节点, Text shouldAutoInvalidate=false),
    // 行号布局全程复用, 万行文件下不再每次按键全量重建
    val numbersText = remember(lineCount) { buildLineNumbers(lineCount) }
    Box(
        Modifier.padding(start = 5.dp),
    ) {
        Text(
            text = numbersText,
            color = colors.secondaryText,
            style = textStyle.copy(fontSize = textStyle.fontSize * 0.6f),
            textAlign = TextAlign.End,
            // 下移基线差与正文行基线对齐 (offset 不参与测量, Box 高度不变, 溢出落在底部内边距内)
            modifier = Modifier
                .padding(end = 11.dp)
                .offset(y = baselineShift),
        )
    }
}

private fun buildLineNumbers(lineCount: Int): String = buildString {
    for (i in 1..lineCount) {
        if (i > 1) append('\n')
        append(i)
    }
}

/** 文本行数 (换行符数 + 1), 仅在外部整体替换时全量统计 */
private fun countLineNumbers(text: String): Int {
    var count = 1
    for (i in text.indices) {
        if (text[i] == '\n') count++
    }
    return count
}

/**
 * 前后文 diff 求新行数增量: 只统计未匹配中段的换行差, 常规按键 (光标处单字符编辑) 为
 * O(编辑距离), 避免每次按键 O(n) 扫描全文; 全量替换等无公共前缀/后缀的改动退化为 O(n)。
 */
private fun newlineDelta(oldText: String, newText: String): Int {
    val minLen = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < minLen && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (suffix < minLen - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) suffix++
    var removed = 0
    for (i in prefix until oldText.length - suffix) {
        if (oldText[i] == '\n') removed++
    }
    var added = 0
    for (i in prefix until newText.length - suffix) {
        if (newText[i] == '\n') added++
    }
    return added - removed
}

/** 全角字符 (CJK/假名/全角符号): 自动补全弹层 X 估算时按 1em 计, 其余按 0.6em */
private fun isFullWidthChar(c: Char): Boolean {
    val code = c.code
    return code >= 0x1100 && (
        code <= 0x115F ||
            code in 0x2E80..0xA4CF ||
            code in 0xAC00..0xD7A3 ||
            code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F ||
            code in 0xFF00..0xFF60 ||
            code >= 0x20000
        )
}

/**
 * 自动补全候选弹层 (对齐原版 AutoCompleteTextView 下拉): 小字列表 + 键盘/点击选中高亮,
 * 确认插入到光标处 ([applyCompletion])。focusable=false 不抢焦点: 键盘事件由字段层
 * onPreviewKeyEvent 共享处理 (原版 popup 同样不独立接收按键), 上下键高亮随 LazyColumn 滚动。
 */
@Composable
private fun AutoCompletePopup(
    matches: List<String>,
    selectedIndex: Int,
    offset: IntOffset,
    onSelect: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    val listState = rememberLazyListState()
    // 键盘导航把高亮项滚进可视区 (对齐原版 AutoCompleteTextView 自动滚动跟随)
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in 0 until matches.size) {
            listState.animateScrollToItem(selectedIndex)
        }
    }
    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        properties = PopupProperties(focusable = false),
        onDismissRequest = {},
    ) {
        Surface(
            color = colors.fillet,
            shape = AppTheme.DesignTokens.shapeDefault,
            elevation = 4.dp,
            modifier = Modifier.width(220.dp),
        ) {
            LazyColumn(Modifier.heightIn(max = 200.dp), state = listState) {
                itemsIndexed(matches) { index, item ->
                    Text(
                        item,
                        color = colors.primaryText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (index == selectedIndex) {
                                    colors.accent.copy(alpha = 0.15f)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { onSelect(index) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Tab 键无候选时插入 "\t" (对齐原版 EditText 硬件 Tab 行为; Compose 字段默认 Tab 走焦点移动, 需消费) */
private fun insertTabAtSelection(value: TextFieldValue): TextFieldValue {
    val sel = value.selection
    val start = sel.min
    return value.copy(
        text = value.text.replaceRange(start, sel.max, "\t"),
        selection = TextRange(start + 1),
    )
}

/**
 * TextMeasurer 实测文本单行宽度; 空串返 0, 超长/异常返 null (调用方回退 0.6em/1em 估算)。
 * [density] 必须传真实屏幕密度 (LocalDensity, 含 fontScale), 否则 sp 字号按 Density(1f)
 * 折算导致宽度整体偏小。
 */
private fun measureTextWidth(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    density: Density,
): Float? {
    if (text.isEmpty()) return 0f
    if (text.length > 300) return null
    return try {
        textMeasurer.measure(
            AnnotatedString(text),
            style = style,
            density = density,
        ).size.width.toFloat()
    } catch (_: Exception) {
        null
    }
}

/** 单行文本首行基线位置 (px, 相对行盒顶); 异常返 0f (调用方容忍对齐误差) */
private fun measureFirstBaseline(
    textMeasurer: TextMeasurer,
    style: TextStyle,
    density: Density,
): Float {
    return try {
        textMeasurer.measure(AnnotatedString("0"), style = style, density = density).firstBaseline
    } catch (_: Exception) {
        0f
    }
}

/**
 * CodeTextField 默认最小高度 = 顶部留白 + 固定行高 (fontSize*1.5) + 底部 4dp:
 * 单行字段高度正好贴合内容, 消除 minHeight 死区 —— 死区是"单行文本离下划线太远"的根源
 * (56dp 最小高 + 顶对齐下, 文本下方空出 56-(顶留白+行高+4dp) ≈ 19dp)。
 * 多行时内容自然超过该值, 高度随内容增长, 文本底距指示线恒为 [TextFieldBottomInset]。
 * (fontScale=1 时 sp 与 dp 等价; 非 1 时此项仅为下限, 内容更高则自动撑开)
 */
private fun codeFieldDefaultMinHeight(hasLabel: Boolean, fontSize: TextUnit): Dp {
    val topSpace =
        if (hasLabel) TextFieldFirstBaselineOffset + TextFieldLabelToText else TextFieldTopPadding
    val lineHeightDp = (if (fontSize.isSp) fontSize.value else 14f) * 1.5f
    return topSpace + lineHeightDp.dp + TextFieldBottomInset
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
    fontSize: TextUnit = 14.sp,
    // 默认最小高度 = 顶留白 + 固定行高 + 底部 4dp, 单行字段高度贴合内容 (消除 minHeight 死区)
    minHeight: Dp = codeFieldDefaultMinHeight(label != null, fontSize),
    searchHighlight: CodeSearchHighlightState? = null,
    autoComplete: Boolean = true,
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
        autoComplete = autoComplete,
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
                        val e = (range.last + 1).coerceAtMost(len)
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
