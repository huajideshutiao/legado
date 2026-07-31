package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 小型组件 Previews 合集:
 * - [AppRadioButton]: 单选钮
 * - [AppNumberField]: 数字输入框
 * - [AppTabRow]/[AppScrollTabRow]: 滚动 tab 行
 * - [DialogTitleBar]: Dialog 标题栏
 * - [AppDropdownMenu]: 下拉菜单容器
 */

// ---- AppRadioButton ----

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

// ---- AppNumberField ----

@Preview
@Composable
fun AppNumberFieldPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("123") }
    Box(Modifier.padding(16.dp)) {
        AppNumberField(
            value = value,
            onValueChange = { value = it },
            label = "数字",
            modifier = Modifier.width(160.dp),
        )
    }
}

// ---- AppScrollTabRow ----

@Preview
@Composable
fun AppScrollTabRowPreview() = LegadoThemePreview {
    var selected by remember { mutableStateOf(1) }
    Box(Modifier.padding(8.dp)) {
        AppScrollTabRow(
            tabCount = 4,
            selectedIndex = selected,
            indicatorColor = io.legado.app.ui.compose.theme.AppTheme.colors.accent,
            modifier = Modifier.fillMaxWidth(),
            tab = { i ->
                Text(
                    text = "Tab ${i + 1}",
                    color = if (i == selected) io.legado.app.ui.compose.theme.AppTheme.colors.accent
                    else io.legado.app.ui.compose.theme.AppTheme.colors.secondaryText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            },
        )
    }
}

// ---- DialogTitleBar ----

@Preview
@Composable
fun DialogTitleBarPreview() = LegadoThemePreview {
    DialogTitleBar(
        title = "对话框标题",
        onBack = {},
    )
}

@Preview
@Composable
fun DialogTitleBarWithSubtitlePreview() = LegadoThemePreview {
    DialogTitleBar(
        title = "对话框标题",
        subtitle = "副标题",
        onBack = {},
    )
}

@Preview
@Composable
fun DialogTitleBarWithActionsPreview() = LegadoThemePreview {
    DialogTitleBar(
        title = "带动作",
        onBack = {},
        actions = {
            Text("动作1", modifier = Modifier.padding(end = 8.dp))
            Text("动作2")
        },
    )
}

@Preview
@Composable
fun DialogTitleBarNoBackPreview() = LegadoThemePreview {
    DialogTitleBar(
        title = "无返回按钮",
        onBack = null,
    )
}

// ---- AppDropdownMenu ----
// AppDropdownMenu 依赖外部 expanded 状态, expanded=false 时不显示内容;
// expanded=true 时在 IDE Preview 中可能无法正确弹出 Popup, 但可预览容器样式。

@Preview
@Composable
fun AppDropdownMenuExpandedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppDropdownMenu(
            expanded = true,
            onDismissRequest = {},
        ) {
            Text("菜单项1", modifier = Modifier.padding(16.dp))
            Text("菜单项2", modifier = Modifier.padding(16.dp))
        }
    }
}
