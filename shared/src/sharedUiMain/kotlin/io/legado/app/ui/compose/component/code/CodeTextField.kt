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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
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
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.legado.app.ui.compose.component.AppDecorationBox
import io.legado.app.ui.compose.component.AppFieldColors
import io.legado.app.ui.compose.component.AppTextFieldImpl
import io.legado.app.ui.compose.component.TextFieldBottomInset
import io.legado.app.ui.compose.component.TextFieldHorizontalPadding
import io.legado.app.ui.compose.component.TextFieldLabelToText
import io.legado.app.ui.compose.component.appFieldDefaultMinHeight
import io.legado.app.ui.compose.component.code.CursorLineBringIntoViewNode.Companion.WaitFrames
import io.legado.app.ui.compose.platform.rememberImeAnimating
import io.legado.app.ui.compose.platform.rememberImeVisible
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

/** 查找面板开着时编辑器文本变化的即时刷新上限, 超长文本等下次面板操作再算 (原版为增量更新) */
private const val SearchRefreshMaxLength = 20000

/**
 * 查找高亮状态 (屏幕级共享): 匹配区间由宿主屏幕的查找目标计算写入,
 * [CodeTextField] 的 VisualTransformation 消费并叠加渲染 (对齐原版 CodeView
 * 的 searchHighlightColor 全量黄底 + currentMatchColor 当前命中强调色)。
 * 属性全 State 委托, 标 @Stable: 宿主重组时含本对象的调用点可跳过。
 */
@Stable
class CodeSearchHighlightState {
    var keyword by mutableStateOf("")
    var useRegex by mutableStateOf(false)
    var matchCase by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var ranges by mutableStateOf(emptyList<IntRange>())
    var currentIndex by mutableIntStateOf(-1)
    var version by mutableIntStateOf(0)
        private set

    /** 输入防抖刷新任务: 见 [refreshDebounced], 聚焦切换时取消避免旧文本结果写入 */
    private var refreshJob: Job? = null

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

    /** 编辑器文本变化时防抖刷新匹配区间 (对齐原版 updateSearchHighlightIncremental
     * 的延迟语义): 输入停顿 [AsyncHighlightDelayMs] 才跑全文匹配, 快速输入不反复同步扫描;
     * [refresh] 内部按内容比较去重, 结果无变化不 bump version */
    fun refreshDebounced(text: String, scope: CoroutineScope) {
        if (keyword.isEmpty() || text.length > SearchRefreshMaxLength) return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(AsyncHighlightDelayMs)
            refresh(text)
        }
    }

    fun clear() {
        // 取消未决的防抖刷新: 聚焦切换到别的字段后, 旧字段的 pending 刷新不得写入
        refreshJob?.cancel()
        refreshJob = null
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
    fontSize: TextUnit = 16.sp,
    // 默认最小高度 = 顶留白 + 固定行高 + 底部 4dp, 单行字段高度贴合内容 (消除 minHeight 死区)
    minHeight: Dp = appFieldDefaultMinHeight(label != null, fontSize),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    searchHighlight: CodeSearchHighlightState? = null,
    autoComplete: Boolean = true,
    /**
     * 键盘弹出动画期间的瞬移滚动器 (可选): 收到光标行**窗口坐标** rect, 滚动容器应以
     * 无动画滚动 (scrollToItem/scrollBy 瞬时) 把该行保持在视口底部上方 —— 视口逐帧收缩
     * 时光标始终可见且无动画滚动互相打断。未提供时动画期间不滚动, 动画结束后一次
     * bringIntoView 收尾。长字段容器接线见各编辑页 (LazyColumn/verticalScroll 的
     * imeScrollNowFor)。
     */
    imeScrollNow: ((Rect) -> Unit)? = null,
) {
    // 着色挂载窗口 (字符偏移区间, 对照原版 activeSyntaxSpans 的渲染窗口 ±20 行):
    // 全文 span 基线照旧维护, 但只有落在本窗口内的 span 会进 AnnotatedString ——
    // 万行文件下 span 从数千降到数十, 文本布局的样式处理成本随之线性下降。
    // 默认全区间: layout 未就绪的首帧与短文本行为与裁剪前一致。
    var renderRange by remember { mutableStateOf(0..Int.MAX_VALUE) }
    // 键盘动画期间冻结挂载窗口更新 (见 onGloballyPositioned): IME 动画中视口逐帧收缩/
    // 扩张, 若跟随会触发数次全量 buildAnnotatedString —— 动画期 (rememberImeAnimating,
    // 事件性, 只在动画边界翻转) 内跳过窗口重算, 动画结束后按最终视口重算一次。
    // 不影响手动滚动 (滚动由宿主滚动容器驱动), 只延迟高亮/行号窗口的校正;
    // 短文本 (renderRange 默认全区间) 无感知。
    val imeAnimating = rememberImeAnimating()
    val transformation = rememberCodeHighlightTransformation(syntax, searchHighlight, renderRange)
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
    // 门槛用内容比较 (非实例同一性): 补全确认 applyMatch / Tab 插入 / 辅助键
    // insertAtCursor/undo/redo/setText 全部绕过增量路径, 且 adjustInput 对多数编辑原样返回
    // 传入实例 (内容变了实例没换), 若以 `!==` 为门槛, 首帧单行后行号计数永远停 1;
    // 内容比较在常规输入下 (增量路径已同步缓存) 与引用比较同价, 直写路径下兜底重算。
    var lastLineCountText by remember { mutableStateOf(value.text) }
    var lineCount by remember { mutableIntStateOf(countLineNumbers(value.text)) }
    // 换行标志随行数增量维护 (行号列可见性判定): 常规编辑在 trackedValueChange 里按
    // newlineDelta 增量更新, 直写路径 (撤销/重载等) 走组合期内容比较兜底 (语义同 lineCount)
    var hasNewline by remember { mutableStateOf(value.text.contains('\n')) }
    if (lastLineCountText != value.text) {
        lastLineCountText = value.text
        lineCount = countLineNumbers(value.text)
        hasNewline = value.text.contains('\n')
    }
    // 拦截节点 (bringIntoView 挂起恢复时) 读最新 selection 的同步数据源 (见 cursorLineRect):
    // 点击/输入在 trackedValueChange 里同步写入 (早于同帧的 bringIntoView 拦截), 外部直写
    // (undo/redo/setText 等不经本字段 onValueChange 的写入) 由组合期赋值兜底
    var latestValue by remember { mutableStateOf(value) }
    if (latestValue != value) latestValue = value
    // 行数增量 + 转发: 文本内容不变时只更新增量, 行号串 (remember(lineCount)) 不重建。
    // lambda 用 remember 固定 + rememberUpdatedState 读最新 onValueChange: 同一实例传给
    // BasicTextField, 无文本变化的重组不触发字段内部额外重建
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val trackedValueChange: (TextFieldValue) -> Unit = remember {
        { newValue: TextFieldValue ->
            // 同步写入拦截节点数据源 (见 latestValue): 点击/输入后同帧的 bringIntoView
            // 拦截必须读到本次 selection 更新, 重组路径 (rememberUpdatedState) 晚一帧
            latestValue = newValue
            val newText = newValue.text
            if (newText != lastLineCountText) {
                val delta = newlineDelta(lastLineCountText, newText)
                lineCount += delta
                // 换行标志增量更新: 新增换行必为多行; 仅当删到行数回 1 (无换行) 才需精确归零,
                // 避免每次按键 O(n) 扫描全文
                if (delta > 0) hasNewline = true
                else if (lineCount <= 1) hasNewline = false
                lastLineCountText = newText
            }
            currentOnValueChange(newValue)
        }
    }
    // 行号列可见性直接按文本内容判定, 与 lineCount 缓存解耦: 缓存可能因直写路径滞后, 若以
    // lineCount > 1 为门槛, "初始单行后内容变多行"时行号列永不出现 (对齐原版 TextWatcher
    // 每次文本变化无条件重算的语义; hasNewline 与 lineCount 同步增量维护, 判定等价 contains)
    val gutterShown = showLineNumbers && hasNewline
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
    // (对齐原版 AutoCompleteAdapter/performFiltering; 行号可不上, 自动补全保留)。
    // 对齐原版触发语义: AutoCompleteTextView 只在**文本变化**后过滤弹出, 点击已有文本
    // 聚焦不弹 (聚焦快照 focusSnapshotText == 当前文本时不弹); 聚焦后未输入时也不弹,
    // 避免点进字段就被候选列表糊脸。
    val isFocused by interactionSource.collectIsFocusedAsState()
    // 聚焦快照: 本字段本次聚焦时的文本。组合期写入, null → 文本 只写一次 (收敛, 不循环重组)
    var focusSnapshotText by remember { mutableStateOf<String?>(null) }
    if (isFocused) {
        if (focusSnapshotText == null) focusSnapshotText = value.text
    } else {
        focusSnapshotText = null
    }
    // (token, 行文本) → 候选快照: 输入快照未变 (光标在 token 内移动/焦点翻转/撤销回退) 时
    // 复用上次 fuzzy 评分结果, 跳过 ~100 词表评分与行内词扫描
    val completionCache = remember { CompletionSnapshotCache() }
    val autoMatches = remember(value.text, value.selection, isFocused, autoComplete, readOnly) {
        if (!autoComplete || !isFocused || readOnly || !value.selection.collapsed) {
            emptyList()
        } else if (focusSnapshotText == value.text) {
            // 聚焦后文本未变: 不弹 (对齐原版仅文本变化触发过滤; 移动光标/纯聚焦不弹)
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
                completionCache.get(token, text.substring(lineStart, lineEnd))
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
    // 字段在窗口中的位置: Popup 的 offset 锚点是窗口内容根 (见 ComposeTextToolbar 的
    // anchorBounds 注释), 自动补全弹层要锚定光标, 组件内相对偏移必须换算成窗口坐标。
    // 常挂跟踪: 挂载即回调一次取最新值 (首次弹层可见时不再用 Zero 错位一帧),
    // 滚动/键盘时位置变化跟随更新; 写入同值不触发重组, 弹层不可见时 (popupOffset
    // 分支短路无读取者) 滚动回调零重组成本
    // 文本布局结果 (BasicTextField onTextLayout 回传): 光标行 rect / 补全弹层锚点 / 行号
    // 逐行对齐全部取自它, 对齐原版 CodeView 一律用 layout.getLineForOffset /
    // getPrimaryHorizontal / getLineBaseline 取几何。逻辑行号 × 固定行高的算法遇软换行
    // (长行折行) 必然偏移, 是"点击内容上跑/光标被键盘挡/补全条跑屏幕底部"的共同根因。
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    /**
     * 只有与当前文本**同步**的布局才可用来按字符偏移取几何。
     *
     * onTextLayout 回传滞后于文本变化一帧: 用旧布局按新文本的偏移取几何会直接越界崩溃
     * (IllegalArgumentException: offset(n) is out of bounds [0, 0] —— 布局还是空文本的,
     * 却被要求算第 n 个字符)。凡是 getLineForOffset / getHorizontalPosition 这类按偏移
     * 查询的调用, 一律只用本值; 按行号查询 (getLineStart/getLineTop) 可用原始布局。
     */
    val syncedLayout = textLayout?.takeIf { it.layoutInput.text.text == value.text }
    // 文本块在字段内的起点 Y (px): contentPadding.top + label 占位, 行内 Y 由 layout 提供
    val textTopPx = with(density) {
        (contentPadding.calculateTopPadding() +
            if (label != null) TextFieldLabelToText else 0.dp).toPx()
    }
    // 字段在窗口中的位置: Popup 的 offset 锚点是窗口内容根 (见 ComposeTextToolbar 的
    // anchorBounds 注释), 自动补全弹层要锚定光标, 组件内相对偏移必须换算成窗口坐标。
    // 常挂跟踪: 挂载即回调一次取最新值 (首次弹层可见时不再用 Zero 错位一帧),
    // 滚动/键盘时位置变化跟随更新; 写入同值不触发重组
    var fieldWindowOffset by remember { mutableStateOf(IntOffset.Zero) }
    val positionTrackingModifier = Modifier.onGloballyPositioned { coords ->
        // 锚点用 positionInWindow (未裁剪): boundsInWindow 会被祖先裁剪模块裁掉,
        // 长字段在滚动容器里向上滚出视口后 top 被夹成视口顶, 弹层锚点随之偏
        val pos = coords.positionInWindow()
        val offset = IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
        if (offset != fieldWindowOffset) fieldWindowOffset = offset
        // IME 动画期间冻结窗口重算: 视口逐帧变化会反复触发下方全量 buildAnnotatedString
        // (见 imeAnimating 声明处); 动画结束后按最终视口重算一次。
        // fieldWindowOffset 更新保留在冻结前: 补全弹层锚点仍需跟随滚动/位置变化。
        if (imeAnimating) return@onGloballyPositioned
        // 可见区间 → 着色挂载窗口 (对照原版 updateVisibleSpans: getLocalVisibleRect +
        // getLineForVertical ±10 行判定 / ±20 行挂载, 窗口仍被覆盖时提前返回不重挂)
        val layout = textLayout ?: return@onGloballyPositioned
        val visible = coords.boundsInWindow()
        if (visible.height <= 0f) return@onGloballyPositioned
        val topInText = visible.top - pos.y - textTopPx
        val bottomInText = visible.bottom - pos.y - textTopPx
        val lastLine = layout.lineCount - 1
        val firstVisible = layout.getLineForVerticalPosition(topInText).coerceIn(0, lastLine)
        val lastVisible = layout.getLineForVerticalPosition(bottomInText).coerceIn(0, lastLine)
        // ±10 行判定窗口仍在已挂载区间内 → 不动 (hysteresis, 滚动不逐帧重挂)
        val keepStart = layout.getLineStart((firstVisible - 10).coerceAtLeast(0))
        val keepEnd = layout.getLineEnd((lastVisible + 10).coerceAtMost(lastLine))
        if (keepStart >= renderRange.first && keepEnd <= renderRange.last) {
            return@onGloballyPositioned
        }
        val start = layout.getLineStart((firstVisible - 20).coerceAtLeast(0))
        val end = layout.getLineEnd((lastVisible + 20).coerceAtMost(lastLine))
        renderRange = start..end
    }
    // 焦点/光标 bringIntoView 滚动目标: 光标所在视觉行 (根 Box 局部坐标)。BasicTextField 获焦时
    // 焦点系统按整个字段 bounds 发起请求, 长字段 (高 > 视口) 会把字段底对齐视口底, 表现
    // 为点击跳到底部; 拦截后统一以光标行为目标, 最多滚动到光标行可见
    val cursorLineRect: () -> Rect? = {
        // 布局过期时返回 null: 拦截节点会退回子请求自身的 bounds (BasicTextField 用其内部
        // 新布局算, 准确) —— 否则按回车后光标仍被软键盘挡住
        val layout = syncedLayout
        // 拦截节点在挂起恢复时 (同帧, 早于重组) 调用本 lambda: 不能用组合期捕获的 value
        // 算 rect (点击后 selection 已更新而重组未跑, 旧 lambda 会算出旧光标行, 长文本下
        // 画面大幅跳错), 一律经 latestValue 取最新 selection。点击只改 selection 不改文本,
        // 旧布局仍与最新文本同步, 可直接按最新 selection 取行; 输入改了文本则旧布局与新文本
        // 不同步, 退回子请求 bounds
        if (layout == null || layout.layoutInput.text.text != latestValue.text) {
            null
        } else {
            val cursor = latestValue.selection.start.coerceIn(0, latestValue.text.length)
            // getLineForOffset 是视觉行 (含软换行), 与原版 layout.getLineForOffset 同语义
            val line = layout.getLineForOffset(cursor)
            Rect(
                left = 0f,
                top = textTopPx + layout.getLineTop(line),
                right = 0f, // 宽度由 responder 节点在请求时以自身实际宽度补齐
                bottom = textTopPx + layout.getLineBottom(line),
            )
        }
    }
    val cursorLineRectState = rememberUpdatedState(cursorLineRect)
    // 光标行窗口 rect (瞬移滚动器输入): 字段局部光标行 + 字段窗口位置 (fieldWindowOffset)。
    // 动画期间字段窗口位置恒定 (视口收缩只改容器高度, 内容坐标不动), 无需逐帧换算;
    // 布局过期帧 (文本刚变) 返回 null, 由调用方跳过本帧
    val cursorLineWindowRect: () -> Rect? = {
        cursorLineRectState.value.invoke()?.let { local ->
            val off = fieldWindowOffset
            Rect(
                off.x + local.left,
                off.y + local.top,
                off.x + local.right,
                off.y + local.bottom,
            )
        }
    }
    // IME 弹出期间把光标行保持可见 (对齐原版 adjustResize + EditText bringPointIntoView:
    // 光标行完全可见则不滚, 不可见才滚到可见)。与旧实现 (只在 imeVisible 翻转时滚一次)
    // 的差异: 订阅文本/选区/焦点/键盘可见/查找激活全部变化 —— 键盘已开时继续输入、光标
    // 下移出可视区、查找命中定位到屏幕外等场景都会重新请求, 恢复原版"光标始终可见"
    // 契约; 滚动幂等语义保证可见时不滚, 连续输入时实际滚动只发生在光标行越界那一刻。
    // 查找定位场景: 面板输入框持焦后字段失焦 (isFocused=false) 但 searchHighlight 仍激活,
    // 需放宽焦点条件, 否则"下一个/上一个"命中屏幕外时选区变了不滚 (原版 focusCurrentMatch
    // 会 bringPointIntoView)。
    val imeRequester = remember { BringIntoViewRequester() }
    // 只按"键盘是否可见/动画边界"翻转触发, 不跟 inset 数值: 键盘弹出是一段动画,
    // imeBottom 每帧都在变, 若作为 key 会逐帧发起 bringIntoView, 滚动请求互相打断
    // —— 表现为弹键盘时明显卡顿。组合期不读 ime 数值 (逐帧重组), 由 rememberImeVisible
    // 在副作用侧订阅翻转 (事件性)。
    // 滚动时机: 键盘弹出动画期间视口逐帧收缩 (宿主 imeDismissPadding 逐帧跟随), 已注册
    // 瞬移滚动器时逐帧无动画跟随 (光标始终可见, 不打断); 动画结束 (imeAnimating
    // true→false, 视口已稳定) 后一次 bringIntoView 收尾 (幂等, 已可见则不滚)。
    val imeVisible = rememberImeVisible()
    val searchActive = searchHighlight != null && searchHighlight.keyword.isNotEmpty()
    LaunchedEffect(value.text, value.selection, isFocused, imeVisible, imeAnimating, searchActive) {
        if (!imeVisible) return@LaunchedEffect
        // 查找定位场景: 字段失焦 (面板持焦) 时仅查找激活才滚
        if (!isFocused && !searchActive) return@LaunchedEffect
        // 键盘弹出动画期间: 视口逐帧收缩, 容器注册了瞬移滚动器时逐帧跟随 —— 光标行始终
        // 可见且无动画滚动打断 (scrollToItem/scrollBy 瞬时, 见 imeScrollNowFor); 未注册时
        // 动画期间不请求 (视口未稳定, 动画滚动互相打断, 见 bringIntoViewOnIme KDoc)
        val scrollNow = imeScrollNow
        if (scrollNow != null && imeAnimating) {
            while (imeAnimating) {
                cursorLineWindowRect()?.let(scrollNow)
                delay(16)
            }
        }
        // 动画结束 (或未注册瞬移滚动器): 一次 bringIntoView 收尾 (幂等, 已可见则不滚;
        // 瞬移路径下光标已可见, 本请求 no-op 兜底)。等布局与当前文本同步后再取光标行
        // 几何: 输入后 onTextLayout 回传滞后一帧, cursorLineRect 在布局过期帧返回 null
        // (旧布局按新偏移取行会越界崩溃); 循环重取最多 3 帧, 仍不同步则放弃本次
        // (下一次文本/选区变化会再触发)
        var rect: Rect? = null
        var frames = 0
        while (frames < 3) {
            rect = cursorLineRectState.value.invoke()
            if (rect != null) break
            withFrameNanos { }
            frames++
        }
        if (rect != null) imeRequester.bringIntoView(rect)
    }
    // 行号列宽 (px, 未显示行号时 null): 5dp 左距 + 号宽 + 11dp 右距 (对齐原版
    // mLineNumberPadding = measureText + 16f*density), 分隔线与补全弹层共用同一实测值。
    // measure 结果 remember: 行数/字号/密度不变时重组直接复用, 不再重复 TextMeasurer.measure
    // (cacheSize=8 LRU, 反复 measure 会互相驱逐)
    val gutterWidthPx: Float? = if (gutterShown) {
        remember(gutterShown, lineCount, codeStyle, density) {
            measureTextWidth(
                textMeasurer,
                lineCount.toString(),
                codeStyle.copy(fontSize = codeStyle.fontSize * 0.6f),
                density
            )?.let { numberWidth ->
                with(density) { (5.dp + 11.dp).toPx() + numberWidth }
            }
        }
    } else null
    // 行号串: 逐**视觉行**一个条目, 只在逻辑行首放行号, 软换行的续行留空 —— 与原版 onDraw
    // 的 isRealLineStart 判定同语义 (续行不画号), 也让行号列与正文逐行严格对齐。
    // layout 未就绪时先按逻辑行数排 (首帧, 随即被 onTextLayout 校正)
    // 行号窗口: 布局过期帧 (文本刚变, onTextLayout 未回传) 复用上次结果, 首帧无历史回退
    // 逻辑行数估算 (见 gutterWindow)
    var lastGutterWindow by remember { mutableStateOf<Triple<String, Float, Int>?>(null) }
    // 行号窗口 (视觉行区间) 与其在文本块内的顶部偏移: 与着色挂载窗口同源 (renderRange),
    // 只为窗口内的行生成行号串 —— 万行文件不再把全部行号做成一个巨大 Text 参与布局
    // (对照原版 onDraw 只画 firstLine..lastLine)。
    val gutterWindow: Triple<String, Float, Int>? = if (!gutterShown) {
        null
    } else {
        // layout 必须与当前文本同步 (syncedLayout): 旧布局按新偏移取行会越界崩溃
        // (offset out of bounds [0,0], 布局还是空文本时), 见 syncedLayout 注释
        val layout = syncedLayout
        if (layout == null) {
            // 布局未就绪 (首帧/刚载入) 按逻辑行数估算; 过期帧复用上次结果, 避免行号串
            // 逐键闪变 (估算串与挂载窗口不匹配)
            lastGutterWindow ?: Triple(buildLineNumbers(lineCount), 0f, 0)
        } else {
            remember(layout, renderRange, gutterShown) {
                val lastLine = layout.lineCount - 1
                val from = layout.getLineForOffset(renderRange.first.coerceIn(0, value.text.length))
                    .coerceIn(0, lastLine)
                val to = layout.getLineForOffset(
                    renderRange.last.coerceIn(0, value.text.length)
                ).coerceIn(from, lastLine)
                Triple(
                    buildVisualLineNumbers(
                        value.text, layout, from, to,
                        logicalLineBefore(value.text, layout, from),
                    ),
                    layout.getLineTop(from),
                    from,
                )
            }.also { lastGutterWindow = it }
        }
    }
    val numbersText = gutterWindow?.first ?: ""
    val numbersTopPx = gutterWindow?.second ?: 0f
    // 行号与正文基线对齐: 两种字号在同一固定行高下由 Compose 各自垂直居中, 基线差 =
    // 各自 firstBaseline 之差 (Roboto 14sp/8.4sp 下约 1.9dp, 即"行号比正文基线偏高"的错位)。
    // 实测消除, 不依赖字体度量假设; 结果同样 remember, 非字号/密度变化不重测
    val gutterBaselineShift: Dp = if (gutterShown) {
        remember(gutterShown, codeStyle, density) {
            with(density) {
                (measureFirstBaseline(textMeasurer, codeStyle, density) -
                    measureFirstBaseline(
                        textMeasurer,
                        codeStyle.copy(fontSize = codeStyle.fontSize * 0.6f),
                        density
                    )).toDp()
            }
        }
    } else {
        0.dp
    }
    // 补全弹层锚点: 布局过期帧复用上次已算好的锚点 (见 popupOffset), 首帧无历史回退 Zero
    var lastPopupOffset by remember { mutableStateOf(IntOffset.Zero) }
    val popupOffset = if (matches.isEmpty()) {
        IntOffset.Zero
    } else {
        // 锚点全部取自真实 layout (对照原版 showDropDown: layout.getLineForOffset +
        // getLineBottom + getPrimaryHorizontal), 逐行硬算遇软换行必偏, 是"补全条跑屏幕
        // 最下方"的根因 —— 锚点算出界后被窗口管理器夹到屏幕底部
        // layout 必须与当前文本同步 (syncedLayout): 旧布局按新偏移取行会越界崩溃
        // (offset out of bounds [0,0], 布局还是空文本时), 见 syncedLayout 注释
        val layout = syncedLayout
        if (layout == null) {
            // 布局未就绪 (首帧) 或过期 (文本刚变, onTextLayout 未回传): 复用上次锚点,
            // 避免弹层逐键闪回字段角落; 无历史 (首帧) 才退回 Zero
            lastPopupOffset
        } else {
            remember(layout, value.selection, gutterWidthPx, contentPadding, density, fieldWindowOffset, label != null) {
                val cursor = value.selection.start.coerceIn(0, value.text.length)
                val line = layout.getLineForOffset(cursor)
                // 弹层挂在光标行下沿 (原版 dropDownAnchor 幽灵 View 覆盖光标行, 下拉在其下方)
                val y = (textTopPx + layout.getLineBottom(line)).roundToInt()
                var x = with(density) {
                    contentPadding.calculateStartPadding(LayoutDirection.Ltr).toPx()
                }.roundToInt()
                // 行号列宽: gutterShown 时文本起点右移 (列宽实测值, 与分隔线同源)
                if (gutterWidthPx != null) {
                    x += gutterWidthPx.roundToInt()
                }
                // 行首到光标的水平位置 (对照原版 getPrimaryHorizontal, 含软换行/双向文本)
                x += layout.getHorizontalPosition(cursor, usePrimaryDirection = true).roundToInt()
                // 弹层锚点换算见 AutoCompletePopup: 组件内偏移 + 字段窗口位置, 由 PopupPositionProvider
                // 减去内容根窗口偏移 (anchorBounds.topLeft) 得最终 offset
                IntOffset(x, y) + fieldWindowOffset
            }.also { lastPopupOffset = it }
        }
    }
    AppTextFieldImpl(
        // 下划线形态统一外围间距 (对齐 AppTextField): 左右下各 4dp;
        // showIndicator=false 内嵌形态 (无下划线) 不加, 由宿主容器自行排版
        modifier = modifier.then(
            if (showIndicator) {
                Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
            } else {
                Modifier
            }
        ),
        isError = isError,
        errorMessage = errorMessage,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent(previewKeyHandler)
                .then(positionTrackingModifier)
                .bringIntoViewRequester(imeRequester)
                .cursorLineBringIntoView(cursorLineRectState)
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
                onTextLayout = { textLayout = it },
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
                                    numbersText = numbersText,
                                    textStyle = codeStyle,
                                    baselineShift = gutterBaselineShift,
                                    topOffsetPx = numbersTopPx,
                                    widthPx = gutterWidthPx,
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
                    // 光标锚点 (窗口坐标): 组件内偏移 + 字段窗口位置
                    popupAnchor = popupOffset,
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
 * [topOffsetPx]: 行号串只覆盖挂载窗口, 用顶部内边距把它压到窗口首行 (padding 参与测量,
 * 列高 = 偏移 + 窗口高, 不会被祖先裁剪吃掉; 固定行高下偏移精确)。
 * [widthPx]: 列宽固定为"最大行号位数"实测值 (与分隔线/弹层锚点同源), 否则窗口内可见
 * 行号位数变化 (99 → 1000) 会让列宽随滚动跳动。
 * 行高与文本一致 (调用方已统一 lineHeight), 随文本同步滚动。
 */
@Composable
private fun LineNumberGutter(
    numbersText: String,
    textStyle: TextStyle,
    baselineShift: Dp,
    topOffsetPx: Float,
    widthPx: Float?,
) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val topOffset = with(density) { topOffsetPx.toDp() }
    Box(
        Modifier
            .padding(start = 5.dp)
            .then(
                if (widthPx != null) {
                    // 列宽减去两侧内边距 (5dp/11dp), 与 gutterWidthPx 的构成一致
                    Modifier.width(with(density) { widthPx.toDp() } - 5.dp)
                } else {
                    Modifier
                }
            ),
    ) {
        Text(
            text = numbersText,
            color = colors.secondaryText,
            style = textStyle.copy(fontSize = textStyle.fontSize * 0.6f),
            textAlign = TextAlign.End,
            // 下移基线差与正文行基线对齐 (offset 不参与测量, 溢出落在底部内边距内)
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topOffset, end = 11.dp)
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

/**
 * 行号串 (逐视觉行, 只覆盖挂载窗口): 只在逻辑行首放行号, 软换行的续行留空行占位 ——
 * 与原版 onDraw 的 isRealLineStart 判定同语义 (`lineStartOffset == 0 ||
 * text[lineStartOffset-1] == '\n'`), 保证行号列与正文逐视觉行严格对齐。
 *
 * 只生成 [fromLine]..[toLine] 的行号 (对照原版 onDraw 只画 firstLine..lastLine 可见行):
 * 万行文件不再把全部行号做成一个巨大 Text 参与布局。调用方按 getLineTop(fromLine)
 * 下移本列即可对位 (固定行高, 偏移精确)。
 *
 * @return 行号串; 首个行号对应的逻辑行号由 [startLogicalLine] 决定
 */
private fun buildVisualLineNumbers(
    text: String,
    layout: TextLayoutResult,
    fromLine: Int,
    toLine: Int,
    startLogicalLine: Int,
): String = buildString {
    var logicalLine = startLogicalLine
    for (i in fromLine..toLine) {
        if (i > fromLine) append('\n')
        val start = layout.getLineStart(i)
        val isRealLineStart = start == 0 || (start in 1..text.length && text[start - 1] == '\n')
        if (isRealLineStart) {
            logicalLine++
            append(logicalLine)
        }
    }
}

/** [line] 之前的逻辑行数 (= 该视觉行所属逻辑行的 0 基序号), 供窗口首行的行号起点定位 */
private fun logicalLineBefore(text: String, layout: TextLayoutResult, line: Int): Int {
    if (line <= 0) return 0
    val offset = layout.getLineStart(line).coerceIn(0, text.length)
    var count = 0
    for (i in 0 until offset) {
        if (text[i] == '\n') count++
    }
    return count
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
 * 定位: 用 [PopupPositionProvider] 把光标锚点 (窗口坐标) 减去内容根窗口偏移
 * (anchorBounds.topLeft, 即 Popup offset 的锚点) 得最终偏移, 滚动时字段位置由
 * onGloballyPositioned 跟踪更新, 弹层跟随光标 (原版 showDropDown 锚定光标行)。
 */
@Composable
private fun AutoCompletePopup(
    matches: List<String>,
    selectedIndex: Int,
    popupAnchor: IntOffset,
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
    val positionProvider = remember(popupAnchor) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                var pos = popupAnchor
                // 返回值就是窗口坐标 (对照同项目 TextToolbarPositionProvider: 内容根偏移
                // anchorBounds.topLeft + 内容根相对坐标 = 窗口坐标)。popupAnchor 已由调用方
                // 用 positionInWindow + 组件内偏移算成窗口绝对坐标, 直接用即可。
                // 旧实现减掉 anchorBounds.topLeft, 等于把窗口坐标退回成字段内局部坐标再当
                // 窗口坐标用: 光标在长文档深处时局部 Y 达数千 px, 越界后被窗口管理器夹到
                // 屏幕底部 —— 这才是"补全条跑屏幕最下方"的真正根因
                // 垂直翻转: 光标下方放不下 → 翻到光标行上方 (对齐原版 PopupWindow
                // showAsDropDown 的翻转, 弹层不超出屏幕底部)
                if (pos.y + popupContentSize.height > windowSize.height) {
                    pos = IntOffset(pos.x, pos.y - popupContentSize.height)
                }
                if (pos.y < 0) pos = IntOffset(pos.x, 0)
                // 水平: 右侧放不下 → 左移贴右缘 (对齐原版 PopupWindow 水平翻转)
                if (pos.x + popupContentSize.width > windowSize.width) {
                    pos = IntOffset((windowSize.width - popupContentSize.width).coerceAtLeast(0), pos.y)
                }
                if (pos.x < 0) pos = IntOffset(0, pos.y)
                return pos
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = false),
        onDismissRequest = {},
    ) {
        Surface(
            color = colors.fillet,
            shape = AppTheme.DesignTokens.shapeDefault,
            elevation = 4.dp,
            modifier = Modifier.width(150.dp),
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

/**
 * bringIntoView 请求拦截: 子节点 (BasicTextField 光标/焦点系统) 的滚动请求到达本节点时,
 * 忽略其按整个字段 bounds 发起的目标, 统一以光标所在行 (本节点局部坐标, 宽 = 节点宽)
 * 向上传播。长字段 (高 > 视口) 点击获焦时只滚光标行可见, 不再整体跳到底部;
 * 输入时光标自身的滚动请求同样收窄为光标行, 行为一致。
 * 光标行 rect 依赖与当前文本同步的布局 (onTextLayout 滞后一帧): 取不到时挂起等帧重取
 * (最多 [WaitFrames] 帧), 仍取不到则**丢弃本次请求** —— 不再退回整个字段 bounds
 * (超高字段的整字段 bounds 无法全部可见, bringIntoView 会把容器滚到字段底部, 即
 * "点输入框界面跳到最底"的根因; 布局就绪后的可见性由 CodeTextField 的滚动订阅补足)。
 */
private class CursorLineBringIntoViewNode(
    internal var cursorLineRect: State<() -> Rect?>,
) : Modifier.Node(), BringIntoViewModifierNode {

    override suspend fun bringIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> Rect?,
    ) {
        // 光标行无法计算 (布局未就绪/与文本不同步) 时等帧重取, 不退回子请求原始 bounds
        var cursorRect: Rect? = null
        var frames = 0
        while (frames < WaitFrames) {
            cursorRect = cursorLineRect.value.invoke()
            if (cursorRect != null) break
            withFrameNanos { }
            frames++
        }
        val rect = cursorRect ?: return
        val target = if (isAttached) {
            val width = requireLayoutCoordinates().size.width.toFloat()
            Rect(0f, rect.top, width, rect.bottom)
        } else {
            rect
        }
        bringIntoView { target }
    }

    private companion object {
        /** 布局同步等待上限 (帧数): 超出后丢弃, 由文本/选区变化的滚动订阅补滚 */
        const val WaitFrames = 5
    }
}

private class CursorLineBringIntoViewElement(
    private val cursorLineRect: State<() -> Rect?>,
) : ModifierNodeElement<CursorLineBringIntoViewNode>() {
    override fun create(): CursorLineBringIntoViewNode =
        CursorLineBringIntoViewNode(cursorLineRect)

    override fun update(node: CursorLineBringIntoViewNode) {
        node.cursorLineRect = cursorLineRect
    }

    override fun equals(other: Any?): Boolean =
        other is CursorLineBringIntoViewElement && other.cursorLineRect === cursorLineRect

    override fun hashCode(): Int = cursorLineRect.hashCode()
}

private fun Modifier.cursorLineBringIntoView(cursorLineRect: State<() -> Rect?>): Modifier =
    this.then(CursorLineBringIntoViewElement(cursorLineRect))

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
    fontSize: TextUnit = 16.sp,
    // 默认最小高度 = 顶留白 + 固定行高 + 底部 4dp, 单行字段高度贴合内容 (消除 minHeight 死区)
    minHeight: Dp = appFieldDefaultMinHeight(label != null, fontSize),
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
    renderRange: IntRange,
): VisualTransformation {
    if (syntax.rules.isEmpty() && search == null) return VisualTransformation.None
    val scope = rememberCoroutineScope()
    val cache = remember(syntax) { CodeHighlightCache(syntax.rules) }
    val version = cache.version
    val searchVersion = search?.version
    val accent = AppTheme.colors.accent
    return remember(cache, version, search, searchVersion, accent, renderRange) {
        // 搜索叠加结果缓存: 防抖窗口内 (同一 pendingText) 文本与搜索版本均未变时,
        // 每次重组复用同一 AnnotatedString 实例, 不再重复 append(全文) + 遍历匹配区间
        var overlayText: String? = null
        var overlaySearchVersion: Int = -1
        var overlayResult: AnnotatedString? = null
        VisualTransformation { text ->
            val base = cache.highlight(text.text, renderRange, scope)
            val searchOverlay =
                search?.takeIf { it.keyword.isNotEmpty() && it.ranges.isNotEmpty() }
            val result = if (searchOverlay == null) {
                base
            } else if (overlayText == text.text && overlaySearchVersion == searchVersion) {
                overlayResult!!
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
                }.also {
                    overlayText = text.text
                    overlaySearchVersion = searchVersion ?: -1
                    overlayResult = it
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
    private var cachedRange: IntRange? = null
    private var cached: AnnotatedString? = null
    private var spans: List<CodeSpan> = emptyList()
    /**
     * spans 的 end 前缀最大值 (单调不减): 二分即可定出"首个可能与窗口相交的 span",
     * 对照原版 updateVisibleSpans 的二分 + 回扫 actualStartIndex, 但为精确 O(log n)
     * (原版回扫到 0 是 O(n))。跨窗口起点的长 span (块注释) 因此不会漏挂。
     */
    private var spanMaxEndPrefix: IntArray = IntArray(0)
    /** 最近一次请求的挂载窗口: 后台着色完成时按它构建挂载结果 */
    private var lastRange: IntRange = 0..Int.MAX_VALUE
    private var pendingText: String? = null
    /** 防抖窗口内的 stale 结果缓存: 同一 pendingText 期间多次 filter 调用复用同一实例 */
    private var staleResult: AnnotatedString? = null
    private var staleRange: IntRange? = null
    private var job: Job? = null

    fun highlight(text: String, range: IntRange, scope: CoroutineScope): AnnotatedString {
        lastRange = range
        if (cachedText == text && cachedRange == range) return cached!!
        if (cachedText == text) {
            // 仅窗口变化 (滚动): span 基线已是最新文本, 直接按新窗口重挂, 不重跑正则
            // (对照原版 updateVisibleSpans 只增删挂载, 不重算 allSyntaxSpans)
            val built = buildAnnotated(text, spans, range)
            cached = built
            cachedRange = range
            return built
        }
        if (pendingText == text) {
            // 防抖窗口内: 复用上次构建的 stale 实例 (输入热路径每键 O(n+窗口 span) → O(1))
            if (staleRange == range) staleResult?.let { return it }
        } else {
            pendingText = text
            staleResult = null
            staleRange = null
            // 单 worker 串行: 任务在跑就不重启 (循环内会自检 pendingText 变化),
            // 不再每次按键 cancel + 新协程 —— 已开始的同步 compute 无法被 cancel 中断,
            // 旧实现串键时会在 Default 池堆积多个全量 diff
            if (job?.isActive != true) {
                job = scope.launch { runHighlightLoop() }
            }
        }
        return staleOrPlain(text, range)
    }

    /**
     * 单 worker 着色循环: 同一时刻至多一个 compute 在跑; 防抖窗口内又输入则放弃本轮,
     * compute 期间又来新输入则丢弃过期结果重来 (提交前核对 pendingText), 过期任务不启动
     * 计算 —— 串键时不再堆积任务, 只有最新文本最终落盘
     */
    private suspend fun runHighlightLoop() {
        while (true) {
            val text = pendingText ?: return
            // 首次加载不延迟 (对齐原版 setText → reHighlightSyntax 立即后台全量)
            delay(if (cachedText == null) 0 else AsyncHighlightDelayMs)
            if (pendingText != text) continue // 防抖窗口内又输入, 放弃本轮
            // 后台只读计算, 字段统一在主线程落盘, 避免并发 compute 写坏增量基线
            val range = lastRange
            val result = withContext(Dispatchers.Default) {
                val newSpans = compute(text)
                Triple(newSpans, prefixMaxEnd(newSpans), buildAnnotated(text, newSpans, range))
            }
            if (pendingText != text) continue // 计算期间又输入, 丢弃过期结果
            spans = result.first
            spanMaxEndPrefix = result.second
            cached = result.third
            cachedText = text
            cachedRange = range
            pendingText = null
            staleResult = null
            staleRange = null
            version++
            return
        }
    }

    private fun compute(text: String): List<CodeSpan> {
        val oldText = cachedText
        val newSpans = if (oldText == null) {
            matchCodeSpans(text, rules)
        } else {
            incremental(oldText, text)
        }
        // 相邻同色区间合并后再入基线: AnnotatedString 相邻同 style 不自动合并,
        // 大文本下 operator 规则产生数千 span, 合并后布局/绘制与下次增量平移都按区间数线性下降
        return mergeAdjacentSpans(newSpans)
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

        // 3. 移除区间内旧 span, 重算区间并归并 (对齐原版 removeAll + addAll + sort;
        // 平移保持有序 + 区间新匹配有序 → 线性归并替代全量 sort, 大文本防抖后少 O(n log n))
        val kept = ArrayList<CodeSpan>(shifted.size + 8)
        for (span in shifted) {
            if (span.start >= lineStart && span.end <= lineEnd) continue
            kept.add(span)
        }
        val added = matchCodeSpans(newText.substring(lineStart, lineEnd), rules)
        val merged = ArrayList<CodeSpan>(kept.size + added.size + 8)
        var a = 0
        var b = 0
        while (a < kept.size && b < added.size) {
            val sa = kept[a]
            val sb = added[b]
            // added 的偏移是子串相对坐标, 比较前必须换算到绝对坐标 (漏 +lineStart 会让
            // 归并顺序错乱, 影响重叠 span 的着色优先级)
            val sbStart = sb.start + lineStart
            val sbEnd = sb.end + lineStart
            val cmp = if (sa.start != sbStart) {
                sa.start - sbStart
            } else if (sa.end != sbEnd) {
                sbEnd - sa.end
            } else {
                0
            }
            if (cmp <= 0) {
                merged.add(sa); a++
            } else {
                merged.add(CodeSpan(sbStart, sbEnd, sb.color)); b++
            }
        }
        while (a < kept.size) merged.add(kept[a++])
        while (b < added.size) {
            val sb = added[b++]
            merged.add(CodeSpan(sb.start + lineStart, sb.end + lineStart, sb.color))
        }
        return merged
    }

    /**
     * 只挂载与 [range] 相交的 span (对照原版 activeSyntaxSpans: 全文 span 基线照旧,
     * 但只有渲染窗口内的才 attach 到 Editable)。起点用 [spanMaxEndPrefix] 二分定位,
     * 跨窗口起点的长 span (块注释/多行字符串) 不会漏挂。
     */
    private fun buildAnnotated(
        text: String,
        spans: List<CodeSpan>,
        range: IntRange,
    ): AnnotatedString {
        if (spans.isEmpty()) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            var i = firstSpanIndexReaching(spans, range.first)
            while (i < spans.size) {
                val span = spans[i]
                if (span.start > range.last) break
                val s = span.start.coerceIn(0, text.length)
                val e = span.end.coerceIn(0, text.length)
                if (s < e) addStyle(SpanStyle(color = span.color), s, e)
                i++
            }
        }
    }

    /**
     * 首个 end > [from] 的 span 下标: 在 end 前缀最大值 (单调不减) 上二分。
     * 前缀数组尚未就绪 (stale 路径的临时 span 集) 时退化为线性扫描。
     */
    private fun firstSpanIndexReaching(spans: List<CodeSpan>, from: Int): Int {
        val prefix = spanMaxEndPrefix
        if (prefix.size != spans.size) {
            var i = 0
            while (i < spans.size && spans[i].end <= from) i++
            return i
        }
        var low = 0
        var high = spans.size - 1
        var result = spans.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (prefix[mid] > from) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    /**
     * 防抖窗口内沿用旧 span, 按变更偏移近似平移 (对齐原版: span 挂在 Editable 上随文本
     * 自动偏移, 新增字符无色), 避免长文本每次按键闪回无色。只平移窗口内的 span:
     * 上次挂载结果本身已是窗口切片, 故此处成本为 O(窗口 span) 而非全文。
     * 结果缓存到 [staleResult]: 同一 pendingText 期间每次 filter 调用复用同一实例。
     */
    private fun staleOrPlain(text: String, range: IntRange): AnnotatedString {
        staleResult = buildStale(text, range)
        staleRange = range
        return staleResult!!
    }

    private fun buildStale(text: String, range: IntRange): AnnotatedString {
        val old = cachedText ?: return AnnotatedString(text)
        val stale = cached ?: return AnnotatedString(text)
        if (stale.spanStyles.isEmpty()) return AnnotatedString(text)
        val minLen = minOf(old.length, text.length)
        var prefix = 0
        while (prefix < minLen && old[prefix] == text[prefix]) prefix++
        val offset = text.length - old.length
        return buildAnnotatedString {
            append(text)
            for (spanRange in stale.spanStyles) {
                if (spanRange.start >= text.length) continue
                var s = spanRange.start
                var e = spanRange.end
                if (s >= prefix) {
                    s = max(prefix, s + offset)
                    e = max(prefix, e + offset)
                } else if (e > prefix) {
                    e = max(prefix, e + offset)
                }
                s = s.coerceIn(0, text.length)
                e = e.coerceIn(0, text.length)
                // 窗口外不挂 (窗口随滚动前移时旧切片可能已越界)
                if (s < e && e > range.first && s <= range.last) {
                    addStyle(spanRange.item, s, e)
                }
            }
        }
    }
}

/** span 的 end 前缀最大值 (单调不减), 供窗口起点二分 */
private fun prefixMaxEnd(spans: List<CodeSpan>): IntArray {
    val out = IntArray(spans.size)
    var maxEnd = Int.MIN_VALUE
    for (i in spans.indices) {
        if (spans[i].end > maxEnd) maxEnd = spans[i].end
        out[i] = maxEnd
    }
    return out
}

/**
 * (token, 行文本) → 候选快照: 输入快照未变 (光标在同 token 内移动/焦点翻转/撤销回退
 * 到相同状态) 时复用上次 fuzzy 评分结果, 跳过 ~100 词表评分与行内词扫描。
 */
private class CompletionSnapshotCache {
    private var token: String? = null
    private var lineText: String? = null
    private var result: List<String> = emptyList()

    fun get(token: String, lineText: String): List<String> {
        if (this.token == token && this.lineText == lineText) return result
        this.token = token
        this.lineText = lineText
        result = findCodeCompletions(token, lineText)
        return result
    }
}
