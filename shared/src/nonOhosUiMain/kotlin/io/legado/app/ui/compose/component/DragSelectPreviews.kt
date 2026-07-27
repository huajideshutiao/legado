package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.legado.app.ui.preview.AppPreview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [DragSelect.kt] 中 [Modifier.dragSelectable] 的 @Preview。
 *
 * dragSelectable 是 Modifier 扩展, 需挂在 LazyColumn 上配合 LazyListState + 协程作用域使用。
 * Preview 中可预览列表视觉, 拖选手势交互受限。
 */

@AppPreview
@Composable
fun DragSelectablePreview() = LegadoThemePreview {
    val data = listOf("项一", "项二", "项三", "项四", "项五")
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selected = remember { mutableStateOf(emptySet<Int>()) }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .dragSelectable(
                listState = listState,
                autoScrollScope = scope,
                isSelected = { selected.value.contains(it) },
                onSelectedChanged = { i, s ->
                    selected.value = if (s) selected.value + i else selected.value - i
                },
            ),
    ) {
        items(data.indices.toList()) { i ->
            Text(
                text = if (selected.value.contains(i)) "✓ ${data[i]}" else data[i],
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
