package io.legado.app.ui.book.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.reorderable.RuleItemScope
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.group_add
import legado.shared.generated.resources.group_manage
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 书架分组管理对话框。
 *
 * 对齐 Android 原版的全高管理结构：标题栏添加入口、拖拽排序、显示开关、独立完整编辑对话框和底部确定按钮。
 */
@Composable
fun GroupManageDialog(
    groups: List<BookGroup>,
    onAddGroup: () -> Unit,
    onEditGroup: (BookGroup) -> Unit,
    onUpdateGroup: (BookGroup) -> Unit,
    onPersistOrder: (List<BookGroup>) -> Unit,
    onDismiss: () -> Unit,
    canAddGroup: suspend () -> Boolean,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var displayGroups by remember(groups) { mutableStateOf(groups) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Box(Modifier.fillMaxSize()) {
            // 全高型: 高度锁定 0.8 屏高
            Surface(
                modifier = Modifier
                    .appDialogSize(fullHeight = true)
                    .align(Alignment.Center),
                shape = DesignTokens.shapeDefault,
                color = colors.fillet,
            ) {
                RuleManageScaffold(
                    items = displayGroups,
                    itemKey = { it.groupId },
                    onMove = { from, to ->
                        displayGroups = displayGroups.toMutableList().apply {
                            add(to, removeAt(from))
                        }
                    },
                    titleBar = {
                        DialogTitleBar(
                            title = stringResource(Res.string.group_manage),
                            onBack = onDismiss,
                            actions = {
                                IconButton(onClick = {
                                    scope.launch {
                                        if (canAddGroup()) {
                                            onAddGroup()
                                        } else {
                                            Toasters.get().toast("分组已达上限(64个)")
                                        }
                                    }
                                }) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_add),
                                        contentDescription = stringResource(Res.string.group_add),
                                        tint = colors.primaryText,
                                    )
                                }
                            },
                        )
                    },
                    actionBar = {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.weight(1f))
                            AppTextButton(text = stringResource(Res.string.ok), onClick = onDismiss)
                        }
                    },
                ) { item ->
                    GroupManageItem(
                        item = item,
                        onShowChange = { show ->
                            displayGroups = displayGroups.map { group ->
                                if (group.groupId == item.groupId) group.copy(show = show) else group
                            }
                            onUpdateGroup(item.copy(show = show))
                        },
                        onEdit = { onEditGroup(item) },
                        onPersistOrder = {
                            val ordered = displayGroups.mapIndexed { index, group ->
                                group.copy(order = index + 1)
                            }
                            displayGroups = ordered
                            onPersistOrder(ordered)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleItemScope.GroupManageItem(
    item: BookGroup,
    onShowChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onPersistOrder: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .longPressDraggableHandle(onDragStopped = onPersistOrder)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.groupName,
            color = colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(
            checked = item.show,
            onCheckedChange = onShowChange,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(Res.string.edit),
            color = colors.secondaryText,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(onClick = onEdit)
                .padding(8.dp),
        )
    }
}
