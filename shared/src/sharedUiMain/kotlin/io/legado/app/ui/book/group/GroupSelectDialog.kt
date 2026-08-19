package io.legado.app.ui.book.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.reorderable.RuleItemScope
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.group_add
import legado.shared.generated.resources.group_select
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 分组选择对话框。
 *
 * 布局与 Android 原版保持一致：标题栏添加入口、可拖拽列表、行内编辑以及底部取消/确定栏。
 */
@Composable
fun GroupSelectDialog(
    groups: List<BookGroup>,
    initialGroupId: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    onPersistOrder: (List<BookGroup>) -> Unit,
    onAddGroup: () -> Unit,
    onEditGroup: (BookGroup) -> Unit,
) {
    val colors = AppTheme.colors
    var groupId by remember(initialGroupId) { mutableLongStateOf(initialGroupId) }
    var displayGroups by remember(groups) { mutableStateOf(groups) }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        // 内容不能套 fillMaxSize: 对话框 layer 的 boundsInWindow 按内容实测尺寸算,
        // 撑满窗口会让整窗都算"框内", 点外部永远触发不了 dismiss。居中由 RootMeasurePolicy 负责。
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column {
                RuleManageScaffold(
                    items = displayGroups,
                    itemKey = { it.groupId },
                    fillMaxHeight = false,
                    // 列表高度自适应内容: 分组少时对话框随内容收缩, 多时由 appDialogSize 的
                    // heightIn(max=0.7×锚点高) 封顶且列表可滚动 (复刻原版 AutoShrinkLinearLayout 语义)
                    wrapContentHeight = true,
                    onMove = { from, to ->
                        displayGroups = displayGroups.toMutableList().apply {
                            add(to, removeAt(from))
                        }
                    },
                    titleBar = {
                        DialogTitleBar(
                            title = stringResource(Res.string.group_select),
                            onBack = onDismiss,
                            actions = {
                                IconButton(onClick = onAddGroup) {
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
                            AppTextButton(
                                text = stringResource(Res.string.cancel),
                                color = colors.secondaryText,
                                onClick = onDismiss,
                            )
                            AppTextButton(text = stringResource(Res.string.ok)) {
                                onConfirm(groupId)
                            }
                        }
                    },
                ) { item ->
                    GroupItem(
                        item = item,
                        checked = (groupId and item.groupId) > 0,
                        onCheckedChange = { checked ->
                            groupId = if (checked) {
                                groupId or item.groupId
                            } else {
                                groupId and item.groupId.inv()
                            }
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
private fun RuleItemScope.GroupItem(
    item: BookGroup,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onPersistOrder: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .longPressDraggableHandle(onDragStopped = onPersistOrder)
            .clickable { onCheckedChange(!checked) }
            // 垂直 padding 去掉: 行高由 M2 Checkbox 48dp 交互区决定 (原版 Material CheckBox
            // 40dp 触摸区 + 8×2 padding ≈ 56dp; 此处去 padding 后 48dp 视觉密度相当)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppCheckbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text(
            text = item.groupName,
            color = colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.edit),
            color = colors.primaryText,
            modifier = Modifier
                .clickable(onClick = onEdit)
                .padding(8.dp),
        )
    }
}
