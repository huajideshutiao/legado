package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = AppTheme.colors

    // Arco 圆角 (硬编码溯源 arco_radius_sm), 显式覆盖 MD2 默认 shapes.small (默认亦 4dp, 此处明示)
    val shape = RoundedCornerShape(4.dp)

    // MD2 TextFieldDefaults.outlinedTextFieldColors (注意: MD2 用 TextFieldDefaults, 非 OutlinedTextFieldDefaults);
    // 参数名: backgroundColor (非 md3 的 containerColor), 单一 cursorColor + errorCursorColor。
    val tfColors = TextFieldDefaults.outlinedTextFieldColors(
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

    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier,
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            maxLines = maxLines,
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            isError = isError,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            shape = shape,
            colors = tfColors,
        )
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
