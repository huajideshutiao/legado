package io.legado.app.ui.compose.component

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * Material Design 2 下划线输入框 (形态依据: 原版 Widget.Design.TextInputLayout boxBackgroundMode=none)。
 *
 * - 透明容器无边框, 仅底部下划线: 未聚焦 1dp controlNormal / 聚焦 2dp accent (indicatorLine 默认粗细)
 * - label 浮动到输入区上方; 配色状态化: 未聚焦/聚焦均为 accent
 * - 文字水平起始 ~4dp (DecorationBox contentPadding 收窄; M2 TextField 默认 16dp 是 filled 容器所需,
 *   下划线形态下会与周边 4dp 对齐的布局明显错位, 如 BookInfoEditScreen 封面按钮行)
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
) {
    AppTextFieldImpl(
        modifier = modifier,
        isError = isError,
        errorMessage = errorMessage,
    ) {
        val colors = AppFieldColors
        val interactionSource = remember { MutableInteractionSource() }
        val textColor = textStyle.color.takeOrElse { colors.textColor(enabled).value }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .indicatorLine(enabled, isError, interactionSource, colors)
                .defaultMinSize(minWidth = TextFieldDefaults.MinWidth),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle.copy(color = textColor),
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
) {
    AppTextFieldImpl(
        modifier = modifier,
        isError = isError,
        errorMessage = errorMessage,
    ) {
        val colors = AppFieldColors
        val interactionSource = remember { MutableInteractionSource() }
        val textColor = textStyle.color.takeOrElse { colors.textColor(enabled).value }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .indicatorLine(enabled, isError, interactionSource, colors)
                .defaultMinSize(
                    minWidth = TextFieldDefaults.MinWidth,
                    minHeight = TextFieldDefaults.MinHeight,
                ),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle.copy(color = textColor),
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

/** 两个重载共享的装饰盒: label 浮动/占位符/图标排版走 M2 默认, 仅水平 contentPadding 收窄至 4dp */
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
        contentPadding = if (label == null) {
            TextFieldDefaults.textFieldWithoutLabelPadding(start = 0.dp, end = 0.dp, bottom = 4.dp)
        } else {
            TextFieldDefaults.textFieldWithLabelPadding(start = 0.dp, end = 0.dp, bottom = 4.dp)
        },
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
