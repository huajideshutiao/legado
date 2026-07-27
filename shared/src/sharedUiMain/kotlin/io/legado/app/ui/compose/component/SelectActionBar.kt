package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/** SelectActionBar 溢出菜单项：文案 + 点击(点击后自动收起菜单) */
data class SelectAction(val text: String, val onClick: () -> Unit)

/**
 * 复刻 View 版 SelectActionBar 语义的底部批量操作栏：
 * 全选复选框(带 已选/总数 计数) + 反选 + 主操作(默认删除) + 溢出菜单。
 * 未选中(selectCount==0)时反选/主操作/菜单置灰不可点，对齐 setMenuClickable。
 *
 * 注: select_cancel_count / select_all_count 原为带 %1$d/%2$d 的格式化文案,
 * commonMain 端 rememberString 仅按 key 取字面量(无 format 参数), 未识别 key 返回 key 本身作 fallback。
 */
@Composable
fun SelectActionBar(
    selectCount: Int,
    allCount: Int,
    onSelectAll: (Boolean) -> Unit,
    onRevertSelection: () -> Unit,
    mainActionText: String,
    onMainAction: () -> Unit,
    modifier: Modifier = Modifier,
    actions: List<SelectAction> = emptyList(),
) {
    val colors = AppTheme.colors
    val isSelectAll = selectCount > 0 && selectCount >= allCount
    val enabled = selectCount > 0
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tpl = if (isSelectAll) "select_cancel_count" else "select_all_count"
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppCheckbox(checked = isSelectAll, onCheckedChange = { onSelectAll(it) })
            Text(
                text = rememberString(tpl),
                color = colors.primaryText,
            )
        }
        AppOutlinedButton(
            text = rememberString("revert_selection"),
            enabled = enabled,
            onClick = onRevertSelection,
        )
        AppOutlinedButton(
            text = mainActionText,
            enabled = enabled,
            modifier = Modifier.padding(start = 4.dp),
            onClick = onMainAction,
        )
        if (actions.isNotEmpty()) {
            var showMenu by remember { mutableStateOf(false) }
            IconButton(onClick = { showMenu = true }, enabled = enabled) {
                Icon(
                    painter = rememberPainter("ic_more_vert"),
                    contentDescription = rememberString("more_menu"),
                    tint = if (enabled) colors.primaryText else colors.secondaryText.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp),
                )
            }
            AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                actions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.text, color = colors.primaryText) },
                        onClick = {
                            showMenu = false
                            action.onClick()
                        },
                    )
                }
            }
        }
    }
}
