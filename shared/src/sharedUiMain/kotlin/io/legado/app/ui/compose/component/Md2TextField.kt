package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppTheme

/**
 * Arco Design 危险色 (= @color/arco_danger #FFF53F3F, arco red-6)。
 * shared KMP 组件层无法访问 R.color, 按既有约定硬编码 + 注释溯源 (见 AppTheme.kt appShapes)。
 */
private val ArcoDangerColor = Color(0xFFF53F3F)

/**
 * Material Design 2 风格输入框 (替代 Compose 默认 md3 OutlinedTextField)。
 *
 * 实现用 androidx.compose.material.OutlinedTextField (MD2) 而非 md3:
 * - MD2 label 聚焦时浮动到边框线缺口 (md3 也会浮动, 但容器/形态不同)
 * - MD2 圆角矩形描边容器, 透明背景 (md3 filled 有浅色填充)
 * - MD2 描边宽度聚焦增粗 (unfocused 1dp / focused 2dp), 由 TextFieldDefaults 控制
 *
 * 配色适配 Arco Design 主题: 聚焦色 = AppTheme.colors.accent (arcoblue-6 #165DFF),
 * 错误色 = Arco danger (#F53F3F)。AppTheme 仅 provides md3 MaterialTheme, 未 provides MD2
 * MaterialTheme.colors, 故不依赖 MD2 MaterialTheme.colors.primary, 而直接用 AppTheme.colors
 * 注入 TextFieldDefaults.outlinedTextFieldColors。
 *
 * 注意: compose.material (MD2) 在 CMP 1.7+ 标记 deprecated 但保留可用 (见 shared/build.gradle)。
 */
@Composable
fun Md2TextField(
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
    Md2TextFieldImpl(
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        isError = isError,
        errorMessage = errorMessage,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = textStyle,
        content = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier,
                enabled = enabled,
                readOnly = readOnly,
                singleLine = singleLine,
                maxLines = maxLines,
                minLines = minLines,
                textStyle = textStyle,
                label = label?.let { { Text(it) } },
                placeholder = placeholder?.let { { Text(it) } },
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                isError = isError,
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                shape = Md2FieldShape,
                colors = Md2FieldColors,
            )
        },
    )
}

/**
 * [Md2TextField] 的 TextFieldValue 重载: 用于需要保留 IME composition / 选区 (undo/redo) 的场景
 * (如 ReplaceEditScreen FormField)。视觉与 String 重载一致。
 */
@Composable
fun Md2TextField(
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
    Md2TextFieldImpl(
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        isError = isError,
        errorMessage = errorMessage,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = textStyle,
        content = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier,
                enabled = enabled,
                readOnly = readOnly,
                singleLine = singleLine,
                maxLines = maxLines,
                minLines = minLines,
                textStyle = textStyle,
                label = label?.let { { Text(it) } },
                placeholder = placeholder?.let { { Text(it) } },
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                isError = isError,
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                shape = Md2FieldShape,
                colors = Md2FieldColors,
            )
        },
    )
}

/** Arco 圆角 (硬编码溯源 arco_radius_sm), 显式覆盖 MD2 默认 shapes.small (默认亦 4dp, 此处明示) */
private val Md2FieldShape = RoundedCornerShape(4.dp)

/**
 * MD2 TextFieldDefaults.outlinedTextFieldColors (注意: MD2 用 TextFieldDefaults, 非 OutlinedTextFieldDefaults);
 * 参数名: backgroundColor (非 md3 的 containerColor), 单一 cursorColor + errorCursorColor。
 */
private val Md2FieldColors
    @Composable get() = AppTheme.colors.let { colors ->
        TextFieldDefaults.outlinedTextFieldColors(
            textColor = colors.primaryText,
            disabledTextColor = colors.secondaryText.copy(alpha = 0.5f),
            backgroundColor = Color.Transparent, // md2 outlined 容器透明无填充
            cursorColor = colors.accent,
            errorCursorColor = ArcoDangerColor,
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.secondaryText,
            disabledBorderColor = colors.secondaryText.copy(alpha = 0.3f),
            errorBorderColor = ArcoDangerColor,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.secondaryText,
            disabledLabelColor = colors.secondaryText.copy(alpha = 0.5f),
            errorLabelColor = ArcoDangerColor,
            placeholderColor = colors.secondaryText,
            disabledPlaceholderColor = colors.secondaryText.copy(alpha = 0.5f),
        )
    }

/** 两个重载共享的容器: OutlinedTextField + 框下错误文案 */
@Composable
private fun Md2TextFieldImpl(
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    label: String?,
    placeholder: String?,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    isError: Boolean,
    errorMessage: String?,
    singleLine: Boolean,
    maxLines: Int,
    minLines: Int,
    visualTransformation: VisualTransformation,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    textStyle: TextStyle,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        content()
        // 错误文案: 框下方 12sp danger 色 (MD2 OutlinedTextField 不自带 errorText, 需手动添加)
        if (isError && !errorMessage.isNullOrEmpty()) {
            Text(
                text = errorMessage,
                color = ArcoDangerColor,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp),
            )
        }
    }
}
