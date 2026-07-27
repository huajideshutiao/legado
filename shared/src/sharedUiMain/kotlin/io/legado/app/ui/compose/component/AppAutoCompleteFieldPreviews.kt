package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
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
 * [AppAutoCompleteField.kt] 中 [AppAutoCompleteField] 的 @Preview。
 *
 * 该组件依赖 Popup (聚焦时弹历史候选下拉), Preview 中可能无法触发 Popup;
 * 但可预览输入框本身的样式。
 */

@Preview
@Composable
fun AppAutoCompleteFieldPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("") }
    Box(Modifier.padding(16.dp)) {
        AppAutoCompleteField(
            value = value,
            onValueChange = { value = it },
            label = "搜索历史",
            values = listOf("三体", "刘慈欣", "科幻小说", "黑暗森林"),
            onDelete = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview
@Composable
fun AppAutoCompleteFieldFilledPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("三") }
    Box(Modifier.padding(16.dp)) {
        AppAutoCompleteField(
            value = value,
            onValueChange = { value = it },
            label = "搜索历史",
            values = listOf("三体", "刘慈欣", "科幻小说", "黑暗森林"),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview
@Composable
fun AppAutoCompleteFieldNoHistoryPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("测试") }
    Box(Modifier.padding(16.dp)) {
        AppAutoCompleteField(
            value = value,
            onValueChange = { value = it },
            label = "无历史",
            values = emptyList(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
