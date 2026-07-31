package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * [RuleManageScaffold.kt] 中 [RuleManageScaffold] 的 @Preview。
 *
 * RuleManageScaffold 是「搜索 + 列表 + 多选 + 拖拽排序 + 底部批量操作栏」共享骨架,
 * Preview 中可预览整体布局结构; 拖拽排序交互受限。
 */

@Preview
@Composable
fun RuleManageScaffoldPreview() = LegadoThemePreview {
    var items by remember { mutableStateOf(listOf("规则一", "规则二", "规则三")) }
    RuleManageScaffold(
        items = items,
        itemKey = { it },
        onMove = { from, to ->
            items = items.toMutableList().apply { add(to, removeAt(from)) }
        },
        modifier = Modifier.fillMaxWidth(),
        titleBar = {
            AppTitleBar(title = "规则管理", onBack = {})
        },
        actionBar = {
            SelectActionBar(
                selectCount = 0,
                allCount = items.size,
                onSelectAll = {},
                onRevertSelection = {},
                mainActionText = "删除",
                onMainAction = {},
                modifier = Modifier.fillMaxWidth(),
            )
        },
        emptyText = "暂无规则",
        itemContent = { item ->
            Text(
                text = item,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        },
    )
}

@Preview
@Composable
fun RuleManageScaffoldEmptyPreview() = LegadoThemePreview {
    RuleManageScaffold(
        items = emptyList<String>(),
        itemKey = { it },
        onMove = { _, _ -> },
        modifier = Modifier.fillMaxWidth(),
        titleBar = {
            AppTitleBar(title = "空列表", onBack = {})
        },
        emptyText = "暂无数据",
        fillMaxHeight = false,
        itemContent = {},
    )
}
