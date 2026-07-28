package io.legado.app.ui.association

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * association 导入弹窗共享模板：标题栏(+菜单槽) + 加载/错误态 + 列表 + 底部(全选/取消/确定)。
 * 各 Import*Dialog 数据结构不同(allSources/allRules)，故列表项 label/state 与勾选状态回调由调用方提供。
 *
 * 下沉 shared/sharedUiMain 后:
 * - `stringResource(R.string.xxx)` → `rememberString("xxx")` (key-based, 跨平台)
 * - 带参数的 `stringResource(R.string.xxx, *args)` → `rememberString("xxx", *args)`
 *   (与 Android `resources.getString(id, *args)` 行为对齐, 由各平台 actual 实现 Formatter)
 * - AppCheckbox/AppTextButton/DialogTitleBar 走 shared/sharedUiMain 的 component 包
 */
@Composable
fun ImportListScaffold(
    title: String,
    loading: Boolean,
    errorText: String?,
    itemCount: Int,
    selectCount: Int,
    isSelectAll: Boolean,
    itemLabel: (index: Int) -> String,
    itemState: (index: Int) -> String,
    itemChecked: (index: Int) -> Boolean,
    onItemChecked: (index: Int, checked: Boolean) -> Unit,
    onOpen: (index: Int) -> Unit,
    onToggleAll: () -> Unit,
    onCancel: () -> Unit,
    onOk: () -> Unit,
    titleActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth()) {
        DialogTitleBar(title = title, actions = titleActions)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
        ) {
            if (errorText != null) {
                Text(
                    text = errorText,
                    color = colors.secondaryText,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            } else {
                FastScrollLazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed((0 until itemCount).toList()) { _, index ->
                        ImportListItem(
                            label = itemLabel(index),
                            state = itemState(index),
                            checked = itemChecked(index),
                            onCheckedChange = { onItemChecked(index, it) },
                            onOpen = { onOpen(index) },
                        )
                    }
                }
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                    color = colors.accent,
                    strokeWidth = 2.dp,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 原 app 端用 stringResource(tpl, selectCount, itemCount) 走 Formatter 填充 %1$d/%2$d,
            // 下沉后用 rememberString(tpl, selectCount, itemCount) 对齐行为
            val tpl = if (isSelectAll) "select_cancel_count" else "select_all_count"
            AppTextButton(
                text = rememberString(tpl, selectCount, itemCount),
                color = colors.secondaryText,
                onClick = onToggleAll,
            )
            Spacer(Modifier.weight(1f))
            AppTextButton(
                text = rememberString("cancel"),
                color = colors.secondaryText,
                onClick = onCancel,
            )
            AppTextButton(text = rememberString("ok"), onClick = onOk)
        }
    }
}

@Composable
private fun ImportListItem(
    label: String,
    state: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppCheckbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = label,
            color = colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        Text(text = state, color = colors.secondaryText, modifier = Modifier.padding(8.dp))
        Text(
            text = rememberString("open"),
            color = colors.secondaryText,
            modifier = Modifier
                .clickable(onClick = onOpen)
                .padding(8.dp),
        )
    }
}
