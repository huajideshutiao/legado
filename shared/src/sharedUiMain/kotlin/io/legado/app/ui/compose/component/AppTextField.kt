package io.legado.app.ui.compose.component

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.material.TextFieldColors
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TextFieldDefaults.indicatorLine
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_baseline_close
import legado.shared.generated.resources.ic_search
import org.jetbrains.compose.resources.painterResource

/**
 * Material Design 2 下划线输入框 (形态依据: 原版 Widget.Design.TextInputLayout boxBackgroundMode=none)。
 *
 * - 透明容器无边框, 仅底部下划线: 未聚焦 1dp controlNormal / 聚焦 2dp accent (indicatorLine 默认粗细)
 * - label 浮动到输入区上方; 配色状态化: 未聚焦/聚焦均为 accent
 * - 文字水平起始 4dp (DecorationBox contentPadding 收窄; M2 TextField 默认 16dp 是 filled 容器所需,
 *   下划线形态下会与周边 4dp 对齐的布局明显错位, 如 BookInfoEditScreen 封面按钮行)
 * - 高度内容推导: 最小高度 = 顶留白 + 固定行高 (fontSize*1.5) + 底部 4dp, 单行字段贴合内容
 *   (对齐 CodeTextField, 消除 56dp minHeight 死区, 文本不再悬浮于线上方)
 * - 行高固定 fontSize*1.5 (16sp → 24sp), 文本底恒距指示线 4dp —— 与 CodeTextField 几何统一
 *
 * 配色适配 Arco Design 主题: 聚焦色 = AppTheme.colors.accent (arcoblue-6 #165DFF),
 * 错误色 = Arco danger (#F53F3F), 直接用 AppTheme.colors 注入 TextFieldDefaults.textFieldColors,
 * 不走 MaterialTheme.colors.primary (取色更明确)。
 *
 * 注意: compose.material (MD2) 在 CMP 1.7+ 标记 deprecated 但保留可用 (见 shared/build.gradle)。
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
    focusRequester: FocusRequester? = null,
) {
    AppTextFieldImpl(
        modifier = modifier,
        isError = isError,
        errorMessage = errorMessage,
    ) {
        val colors = AppFieldColors
        val interactionSource = remember { MutableInteractionSource() }
        val textColor = textStyle.color.takeOrElse { colors.textColor(enabled).value }
        // 固定行高 fontSize*1.5 (对齐 CodeTextField): 单行/多行垂直几何统一, 行高不随字体默认漂移
        val effectiveTextStyle = textStyle.copy(
            lineHeight = textStyle.fontSize.takeOrElse { 16.sp } * 1.5f,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .indicatorLine(enabled, isError, interactionSource, colors)
                // 最小高度内容推导 (对齐 CodeTextField): 单行字段贴合内容, 消除 56dp 死区
                .defaultMinSize(
                    minWidth = TextFieldDefaults.MinWidth,
                    minHeight = appFieldDefaultMinHeight(
                        label != null,
                        textStyle.fontSize.takeOrElse { 16.sp },
                    ),
                ),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = effectiveTextStyle.copy(color = textColor),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.cursorColor(isError).value),
            decorationBox = { innerTextField ->
                AppDecorationBox(
                    text = value,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    isError = isError,
                    label = label,
                    placeholder = placeholder,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    colors = colors,
                )
            },
        )
    }
}

/**
 * [AppTextField] 的 TextFieldValue 重载: 用于需要保留 IME composition / 选区 (undo/redo) 的场景
 * (如 ReplaceEditScreen FormField)。视觉与 String 重载一致。
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun AppTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
    focusRequester: FocusRequester? = null,
) {
    AppTextFieldImpl(
        modifier = modifier,
        isError = isError,
        errorMessage = errorMessage,
    ) {
        val colors = AppFieldColors
        val interactionSource = remember { MutableInteractionSource() }
        val textColor = textStyle.color.takeOrElse { colors.textColor(enabled).value }
        // 固定行高 fontSize*1.5 (对齐 CodeTextField): 单行/多行垂直几何统一, 行高不随字体默认漂移
        val effectiveTextStyle = textStyle.copy(
            lineHeight = textStyle.fontSize.takeOrElse { 16.sp } * 1.5f,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .indicatorLine(enabled, isError, interactionSource, colors)
                // 最小高度内容推导 (对齐 CodeTextField): 单行字段贴合内容, 消除 56dp 死区
                .defaultMinSize(
                    minWidth = TextFieldDefaults.MinWidth,
                    minHeight = appFieldDefaultMinHeight(
                        label != null,
                        textStyle.fontSize.takeOrElse { 16.sp },
                    ),
                ),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = effectiveTextStyle.copy(color = textColor),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.cursorColor(isError).value),
            decorationBox = { innerTextField ->
                AppDecorationBox(
                    text = value.text,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    isError = isError,
                    label = label,
                    placeholder = placeholder,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    colors = colors,
                )
            },
        )
    }
}

// ===== 下划线输入框统一几何 (与 component/code/CodeTextField 共用同一套常量) =====

/** 水平内容留白: 下划线形态收窄至 4dp (M2 filled 默认 16dp 是容器形态所需) */
internal val TextFieldHorizontalPadding = 4.dp

/** 无 label 时文本顶部留白 (M2 textFieldWithoutLabelPadding 默认 top = TextFieldPadding = 16dp) */
internal val TextFieldTopPadding = 16.dp

/** 有 label 时 label 基线距组件顶 (M2 FirstBaselineOffset = 20dp) */
internal val TextFieldFirstBaselineOffset = 20.dp

/** label 基线到文本顶的间距 (M2 内部 TextFieldTopPadding = 2dp, label 浮动后文本顶 = 基线 + 2dp) */
internal val TextFieldLabelToText = 2.dp

/**
 * 文本底到指示线的间距 4dp (原版 EditText wrap_content 底部 inset 约 2-4dp)。
 * 顶对齐内容下 bottom 即"文本-下划线"距离。
 */
internal val TextFieldBottomInset = 4.dp

/**
 * 输入框默认最小高度 = 顶留白 + 固定行高 (fontSize*1.5) + 底部 4dp:
 * 单行字段高度正好贴合内容, 消除 minHeight 死区 —— 死区是"单行文本离下划线太远"的根源
 * (56dp 最小高 + 顶对齐下, 文本下方空出 56-(顶留白+行高+4dp) ≈ 19dp)。
 * 多行时内容自然超过该值, 高度随内容增长, 文本底距指示线恒为 [TextFieldBottomInset]。
 * (fontScale=1 时 sp 与 dp 等价; 非 1 时此项仅为下限, 内容更高则自动撑开)
 */
internal fun appFieldDefaultMinHeight(hasLabel: Boolean, fontSize: TextUnit): Dp {
    val topSpace =
        if (hasLabel) TextFieldFirstBaselineOffset + TextFieldLabelToText else TextFieldTopPadding
    val lineHeightDp = (if (fontSize.isSp) fontSize.value else 14f) * 1.5f
    return topSpace + lineHeightDp.dp + TextFieldBottomInset
}

/**
 * MD2 filled 系 TextFieldDefaults.textFieldColors + backgroundColor=Transparent = 下划线形态取色。
 * 下划线 = indicator 系参数 (非 outlined 的 border 系); 单一 cursorColor + errorCursorColor。
 * internal: 供 component/code 的 CodeTextField 复用同一套取色。
 */
internal val AppFieldColors: TextFieldColors
    @Composable get() = AppTheme.colors.let { colors ->
        TextFieldDefaults.textFieldColors(
            textColor = colors.primaryText,
            disabledTextColor = colors.textDisabled,
            backgroundColor = Color.Transparent, // boxBackgroundMode=none: 容器透明无填充
            cursorColor = colors.accent,
            errorCursorColor = DesignTokens.arcoDanger,
            focusedIndicatorColor = colors.accent,
            unfocusedIndicatorColor = colors.controlNormal,
            disabledIndicatorColor = colors.textDisabled,
            errorIndicatorColor = DesignTokens.arcoDanger,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.accent,
            disabledLabelColor = colors.textDisabled,
            errorLabelColor = DesignTokens.arcoDanger,
            placeholderColor = colors.controlNormal,
            disabledPlaceholderColor = colors.textDisabled,
        )
    }

/** 两个重载共享的装饰盒: label 浮动/占位符/图标排版走 M2 默认, 仅水平 contentPadding 收窄至 4dp。
 *  [contentPadding] 可覆写: CodeTextField 盒模式传入含 16dp 盒内边距的 padding */
@OptIn(ExperimentalMaterialApi::class)
@Composable
internal fun AppDecorationBox(
    text: String,
    innerTextField: @Composable () -> Unit,
    enabled: Boolean,
    singleLine: Boolean,
    visualTransformation: VisualTransformation,
    interactionSource: InteractionSource,
    isError: Boolean,
    label: String?,
    placeholder: String?,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    colors: TextFieldColors,
    // 垂直 top 走 M2 默认 (无 label 16dp / 有 label FirstBaselineOffset+2dp), bottom 固定 4dp
    // (TextFieldBottomInset), 水平 4dp (TextFieldHorizontalPadding) —— 对齐 CodeTextField,
    // 文本底恒距指示线 4dp, 与 M2 默认 (bottom 10dp / 水平 16dp) 不同。
    contentPadding: PaddingValues = if (label == null) {
        TextFieldDefaults.textFieldWithoutLabelPadding(
            start = TextFieldHorizontalPadding,
            end = TextFieldHorizontalPadding,
            bottom = TextFieldBottomInset,
        )
    } else {
        TextFieldDefaults.textFieldWithLabelPadding(
            start = TextFieldHorizontalPadding,
            end = TextFieldHorizontalPadding,
            bottom = TextFieldBottomInset,
        )
    },
) {
    TextFieldDefaults.TextFieldDecorationBox(
        value = text,
        innerTextField = innerTextField,
        enabled = enabled,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        isError = isError,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        colors = colors,
        contentPadding = contentPadding,
    )
}

/** 两个重载共享的容器: 下划线输入框 + 线下错误文案 */
@Composable
internal fun AppTextFieldImpl(
    modifier: Modifier,
    isError: Boolean,
    errorMessage: String?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = AppTheme.colors.accent,
            backgroundColor = AppTheme.colors.accent.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier) {
            content()
            // 错误文案: 下划线下方 12sp danger 色 (MD2 不自带 errorText, 需手动添加); 4dp 对齐文字起始
            if (isError && !errorMessage.isNullOrEmpty()) {
                Text(
                    text = errorMessage,
                    color = DesignTokens.arcoDanger,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

// ===== @Preview 合并自 androidMain 的 compose/component/AppTextFieldPreviews.kt =====

/**
 * [AppTextField] 的 @Preview: 浅色/深色/错误态/禁用态/带图标/占位符/多行。
 */

@Preview
@Composable
fun AppTextFieldLightPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("输入内容") }
    Box(Modifier.padding(16.dp)) {
        AppTextField(
            value = value,
            onValueChange = { value = it },
            label = "标签",
            placeholder = "请输入",
            singleLine = true,
            modifier = Modifier.width(240.dp),
        )
    }
}

@Preview
@Composable
fun AppTextFieldDarkPreview() = LegadoThemePreview(dark = true) {
    var value by remember { mutableStateOf("输入内容") }
    Box(Modifier.padding(16.dp)) {
        AppTextField(
            value = value,
            onValueChange = { value = it },
            label = "标签",
            placeholder = "请输入",
            singleLine = true,
            modifier = Modifier.width(240.dp),
        )
    }
}

@Preview
@Composable
fun AppTextFieldPlaceholderPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("") }
    Box(Modifier.padding(16.dp)) {
        AppTextField(
            value = value,
            onValueChange = { value = it },
            label = "标签",
            placeholder = "请输入内容",
            singleLine = true,
            modifier = Modifier.width(240.dp),
        )
    }
}

@Preview
@Composable
fun AppTextFieldErrorPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("bad") }
    Box(Modifier.padding(16.dp)) {
        AppTextField(
            value = value,
            onValueChange = { value = it },
            label = "标签",
            isError = true,
            errorMessage = "内容不合法, 请重新输入",
            singleLine = true,
            modifier = Modifier.width(240.dp),
        )
    }
}

@Preview
@Composable
fun AppTextFieldDisabledPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppTextField(
            value = "不可编辑",
            onValueChange = {},
            enabled = false,
            label = "标签",
            singleLine = true,
            modifier = Modifier.width(240.dp),
        )
    }
}

@Preview
@Composable
fun AppTextFieldWithIconsPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("关键字") }
    val colors = AppTheme.colors
    Box(Modifier.padding(16.dp)) {
        AppTextField(
            value = value,
            onValueChange = { value = it },
            label = "搜索",
            placeholder = "请输入关键字",
            singleLine = true,
            leadingIcon = {
                Icon(
                    painter = painterResource(Res.drawable.ic_search),
                    contentDescription = null,
                    tint = colors.secondaryText,
                )
            },
            trailingIcon = {
                Icon(
                    painter = painterResource(Res.drawable.ic_baseline_close),
                    contentDescription = null,
                    tint = colors.secondaryText,
                )
            },
            modifier = Modifier.width(280.dp),
        )
    }
}

@Preview
@Composable
fun AppTextFieldMultilinePreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("第一行\n第二行") }
    Box(Modifier.padding(16.dp)) {
        AppTextField(
            value = value,
            onValueChange = { value = it },
            label = "多行输入",
            placeholder = "请输入",
            maxLines = 4,
            modifier = Modifier.width(280.dp),
        )
    }
}

@Preview
@Composable
fun AppTextFieldGalleryPreview() = LegadoThemePreview {
    var normal by remember { mutableStateOf("普通") }
    var empty by remember { mutableStateOf("") }
    Column(
        Modifier.padding(16.dp).width(280.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTextField(
            value = normal,
            onValueChange = { normal = it },
            label = "普通",
            placeholder = "请输入",
            singleLine = true,
        )
        AppTextField(
            value = empty,
            onValueChange = { empty = it },
            label = "占位符",
            placeholder = "请输入内容",
            singleLine = true,
        )
        AppTextField(
            value = "错误内容",
            onValueChange = {},
            label = "错误",
            isError = true,
            errorMessage = "校验失败",
            singleLine = true,
        )
        AppTextField(
            value = "禁用",
            onValueChange = {},
            enabled = false,
            label = "禁用",
            singleLine = true,
        )
    }
}

@Preview
@Composable
fun AppTextFieldValuePreview() = LegadoThemePreview {
    var value by remember { mutableStateOf(TextFieldValue("TextFieldValue 内容")) }
    Box(Modifier.padding(16.dp)) {
        AppTextField(
            value = value,
            onValueChange = { value = it },
            label = "TextFieldValue 重载",
            placeholder = "请输入",
            singleLine = true,
            modifier = Modifier.width(280.dp),
        )
    }
}
