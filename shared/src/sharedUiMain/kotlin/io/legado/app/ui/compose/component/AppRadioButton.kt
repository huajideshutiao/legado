package io.legado.app.ui.compose.component

import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.theme.AppTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.preview.LegadoThemePreview

/** 单选钮：accent 选中色对齐 AppCompatRadioButton 主题着色（形态与 MD2 一致，仅中和取色） */
@Composable
fun AppRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    RadioButton(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = RadioButtonDefaults.colors(
            selectedColor = colors.accent,
            unselectedColor = colors.secondaryText,
        ),
    )
}

// ===== @Preview 合并自 androidMain 的 compose/component/SmallComponentsPreviews.kt (AppRadioButton) =====

// ---- AppRadioButton ----

/**
 * 小型组件 Previews 合集:
 * - [AppRadioButton]: 单选钮
 * - [AppNumberField]: 数字输入框
 * - [AppTabRow]/[AppScrollTabRow]: 滚动 tab 行
 * - [DialogTitleBar]: Dialog 标题栏
 * - [AppDropdownMenu]: 下拉菜单容器
 */

@Preview
@Composable
fun AppRadioButtonSelectedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppRadioButton(selected = true, onClick = {})
    }
}

@Preview
@Composable
fun AppRadioButtonUnselectedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppRadioButton(selected = false, onClick = {})
    }
}

@Preview
@Composable
fun AppRadioButtonDisabledPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppRadioButton(selected = true, onClick = null, enabled = false)
    }
}

