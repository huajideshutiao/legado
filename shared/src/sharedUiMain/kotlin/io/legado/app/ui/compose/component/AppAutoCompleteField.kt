package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_clear_all
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 复刻 widget.text.AutoCompleteTextView（DialogEditTextBinding 的历史下拉输入框）：
 * 聚焦/输入时弹历史候选下拉，每项可选删除按钮（onDelete != null 时显示，删除后从下拉移除并回调）。
 * 下拉不抢焦点，输入可持续过滤（contains，对齐历史建议语义）。
 */
@Composable
fun AppAutoCompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    values: List<String> = emptyList(),
    onDelete: ((String) -> Unit)? = null,
    singleLine: Boolean = true,
    autoFocus: Boolean = false,
) {
    val colors = AppTheme.colors
    val history = remember { mutableStateListOf<String>().apply { addAll(values) } }
    LaunchedEffect(values) {
        history.clear(); history.addAll(values)
    }
    var focused by remember { mutableStateOf(false) }
    val suggestions = history.filter { focused && (value.isEmpty() || it.contains(value, ignoreCase = true)) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }
    Box(modifier) {
        AppOutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
            label = label,
            singleLine = singleLine,
        )
        if (suggestions.isNotEmpty()) {
            Popup(
                alignment = Alignment.TopStart,
                properties = PopupProperties(focusable = false),
                onDismissRequest = { focused = false },
            ) {
                Surface(
                    color = colors.fillet,
                    elevation = 4.dp,
                    modifier = Modifier.fillMaxWidth(0.9f),
                ) {
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(suggestions, key = { it }) { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onValueChange(item) }
                                    .padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    item,
                                    color = colors.primaryText,
                                    fontSize = 14.sp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 12.dp),
                                )
                                if (onDelete != null) {
                                    IconButton(onClick = {
                                        history.remove(item)
                                        onDelete(item)
                                    }) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_clear_all),
                                            contentDescription = null,
                                            tint = colors.secondaryText,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===== @Preview 合并自 androidMain 的 compose/component/AppAutoCompleteFieldPreviews.kt =====

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
