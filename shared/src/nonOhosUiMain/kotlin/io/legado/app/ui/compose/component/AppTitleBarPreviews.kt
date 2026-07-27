package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.legado.app.ui.preview.AppPreview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [AppTitleBar.kt] 中各 Composable 的 @Preview。
 * - [AppTitleBar]: 顶栏 (标题/返回/动作槽)
 * - [AppSearchField]: 搜索框
 * - [OverflowMenu]: 溢出菜单
 */

@AppPreview
@Composable
fun AppTitleBarPreview() = LegadoThemePreview {
    AppTitleBar(
        title = "页面标题",
        onBack = {},
    )
}

@AppPreview
@Composable
fun AppTitleBarDarkPreview() = LegadoThemePreview(dark = true) {
    AppTitleBar(
        title = "深色页面标题",
        onBack = {},
    )
}

@AppPreview
@Composable
fun AppTitleBarWithActionsPreview() = LegadoThemePreview {
    AppTitleBar(
        title = "带动作页标题",
        onBack = {},
        actions = {
            Text("动作1", modifier = Modifier.padding(end = 8.dp))
            Text("动作2")
        },
    )
}

@AppPreview
@Composable
fun AppTitleBarWithSearchContentPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("") }
    AppTitleBar(
        title = "搜索",
        onBack = {},
        titleContent = {
            AppSearchField(
                value = value,
                onValueChange = { value = it },
                hint = "搜索书籍/作者",
            )
        },
    )
}

@AppPreview
@Composable
fun AppSearchFieldFilledPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppSearchField(
            value = "已输入文本",
            onValueChange = {},
            hint = "搜索书籍/作者",
            onSearch = {},
        )
    }
}

@AppPreview
@Composable
fun OverflowMenuPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        OverflowMenu { dismiss ->
            DropdownMenuItem(
                onClick = { dismiss() },
            ) {
                Text("菜单项1")
            }
            DropdownMenuItem(
                onClick = { dismiss() },
            ) {
                Text("菜单项2")
            }
        }
    }
}
