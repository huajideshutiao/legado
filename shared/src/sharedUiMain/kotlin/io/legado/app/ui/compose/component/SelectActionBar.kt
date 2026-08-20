package io.legado.app.ui.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.more_menu
import legado.shared.generated.resources.revert_selection
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** SelectActionBar 溢出菜单项：文案 + 点击(点击后自动收起菜单) */
data class SelectAction(val text: String, val onClick: () -> Unit)

/**
 * 复刻 View 版 SelectActionBar 语义的底部批量操作栏：
 * 全选复选框(带 已选/总数 计数) + 反选 + 主操作(默认删除) + 溢出菜单。
 * 未选中(selectCount==0)时反选/主操作/菜单置灰不可点，对齐 setMenuClickable。
 *
 * 注: select_cancel_count / select_all_count 为带 %1$d/%2$d 的格式化文案,
 * 调用 rememberString 时需传入 selectCount/allCount 由 Formatter 填充占位符。
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
    // 对齐原版 init: 无壁纸时涂 bottomBackground, 有壁纸时透明
    val themeStore = LocalThemeStoreProvider.current
    val hasBgImage = remember(themeStore) {
        !themeStore.bgImagePath.isNullOrBlank()
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(if (hasBgImage) Color.Transparent else colors.bottomBackground)
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
                text = rememberString(tpl, selectCount, allCount),
                color = colors.primaryText,
            )
        }
        AppOutlinedButton(
            text = stringResource(Res.string.revert_selection),
            enabled = enabled,
            // 对齐原版 xml: 按钮带 4dp margin (arco_spacing_xs)
            modifier = Modifier.padding(start = 4.dp),
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
            // 菜单必须与更多按钮处于同一锚点 Box；若作为 Row 的并列节点，
            // DropdownMenu 会拿到错误的父级坐标，表现为从操作栏左侧弹出。
            Box {
                // 对齐原版 xml ivMenuMore 36dp, 而非 M2 IconButton 默认 48dp
                IconButton(onClick = { showMenu = true }, enabled = enabled, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_more_vert),
                        contentDescription = stringResource(Res.string.more_menu),
                        tint = if (enabled) colors.primaryText else colors.secondaryText.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp),
                    )
                }
                AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            onClick = {
                                showMenu = false
                                action.onClick()
                            },
                        ) {
                            Text(action.text, color = colors.primaryText)
                        }
                    }
                }
            }
        }
    }
}
