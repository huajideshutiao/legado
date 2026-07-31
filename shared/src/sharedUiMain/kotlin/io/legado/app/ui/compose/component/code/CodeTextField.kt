package io.legado.app.ui.compose.component.code

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LocalTextStyle
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppDecorationBox
import io.legado.app.ui.compose.component.AppFieldColors
import io.legado.app.ui.compose.component.AppTextFieldImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 超过此长度改走后台着色, 避免主线程正则拖慢每次按键 */
private const val AsyncHighlightThreshold = 5000

/** 后台着色前的等待, 对齐 CodeView 的 postDelayed(highlightRunnable, 150) */
private const val AsyncHighlightDelayMs = 150L

/**
 * 语法高亮代码输入框 (KMP 共享), 替代 app 端 `ui/widget/code/CodeView` (EditText 子类)。
 *
 * 视觉对齐 [io.legado.app.ui.compose.component.AppTextField]: 透明容器 + 底部下划线
 * (未聚焦 controlNormal / 聚焦 accent), 差异仅在等宽字体与语法着色。
 * [showIndicator] = false 时去掉下划线, 用于对话框内嵌 (对齐原版 CodeDialog 无边框呈现)。
 *
 * @param syntax 着色规则集, 由调用方按字段类型传 (见 [rememberCodeSyntax])
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
    fontSize: TextUnit = 14.sp,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val transformation = rememberCodeHighlightTransformation(syntax)
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
        val textColor = baseStyle.color.takeOrElse { colors.textColor(enabled).value }
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
                .defaultMinSize(minWidth = TextFieldDefaults.MinWidth),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = baseStyle.copy(color = textColor),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = false,
            maxLines = Int.MAX_VALUE,
            visualTransformation = transformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.cursorColor(isError).value),
            decorationBox = { innerTextField ->
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
            },
        )
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
    fontSize: TextUnit = 14.sp,
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
        fontSize = fontSize,
    )
}

/**
 * 语法着色 [VisualTransformation]。
 *
 * VisualTransformation.filter 是同步 API, 无法 await 后台结果, 因此长文本走"先旧后新":
 * 缓存未命中时立即返回沿用上次 span 的近似结果 (超出新文本长度的 span 截断), 后台算完
 * bump version 触发重组, 届时命中缓存拿到精确着色。短文本 (<[AsyncHighlightThreshold]) 同步算。
 */
@Composable
private fun rememberCodeHighlightTransformation(syntax: CodeSyntaxScheme): VisualTransformation {
    if (syntax.rules.isEmpty()) return VisualTransformation.None
    val scope = rememberCoroutineScope()
    val cache = remember(syntax) { CodeHighlightCache(syntax.rules) }
    val version = cache.version
    return remember(cache, version) {
        VisualTransformation { text ->
            TransformedText(cache.highlight(text.text, scope), OffsetMapping.Identity)
        }
    }
}

private class CodeHighlightCache(private val rules: List<CodeSyntaxRule>) {

    /** 后台着色完成后自增, 供 Compose 侧重建 VisualTransformation 触发重绘 */
    var version by mutableIntStateOf(0)
        private set

    private var cachedHash: Int? = null
    private var cached: AnnotatedString? = null
    private var pendingHash: Int? = null
    private var job: Job? = null

    fun highlight(text: String, scope: CoroutineScope): AnnotatedString {
        val hash = text.hashCode()
        cached?.let { if (hash == cachedHash && it.text == text) return it }
        if (text.length < AsyncHighlightThreshold) {
            return buildHighlightedCode(text, rules).also {
                cachedHash = hash
                cached = it
                pendingHash = null
            }
        }
        if (pendingHash != hash) {
            pendingHash = hash
            job?.cancel()
            job = scope.launch {
                delay(AsyncHighlightDelayMs)
                val result = withContext(Dispatchers.Default) { buildHighlightedCode(text, rules) }
                cachedHash = hash
                cached = result
                version++
            }
        }
        return staleOrPlain(text)
    }

    /** 沿用上次 span 覆盖新文本, 避免长文本每次按键闪回无色 */
    private fun staleOrPlain(text: String): AnnotatedString {
        val stale = cached ?: return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            for (range in stale.spanStyles) {
                if (range.start >= text.length) continue
                addStyle(range.item, range.start, range.end.coerceAtMost(text.length))
            }
        }
    }
}
