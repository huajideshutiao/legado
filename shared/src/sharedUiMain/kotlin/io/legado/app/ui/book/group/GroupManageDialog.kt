package io.legado.app.ui.book.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 书架分组管理对话框 (KMP 版, commonMain 下沉供 app/desktop 共用)。
 *
 * # 背景
 *
 * 对照 app 端 [GroupManageDialog] (BaseComposeDialogFragment):
 * - 原版依赖 Fragment + RuleManageScaffold (拖拽排序) + GroupEditDialog (独立编辑弹窗),
 *   含 Android 专属 API (viewModels/showDialogFragment/requireContext)。
 * - 桌面端无 Fragment 体系, 改为纯 @Composable + [Dialog], 由调用方注入数据与回调。
 *
 * # API 设计
 *
 * 数据与持久化全部由调用方注入 (GroupViewModelShared), 本组件仅负责 UI 与交互:
 * - [groups]: 分组列表 (调用方订阅 bookGroupDao.flowAll() 提供)
 * - [onAddGroup]: 新建分组 (传入分组名, 调用方调 GroupViewModelShared.addGroup)
 * - [onRenameGroup]: 重命名 (传入 groupId + 新名, 调用方调 GroupViewModelShared.upGroup)
 * - [onDeleteGroup]: 删除 (传入 groupId, 调用方调 GroupViewModelShared.delGroup)
 *
 * # 简化项 (对照 app 端原版)
 *
 * - 不接入拖拽排序 (reorderable 依赖较重, 桌面端可后续按需接入)
 * - 不接入显示开关 (AppSwitch), 原 API 无 onToggleShow 回调, 后续可扩展
 * - 编辑/新建分组: 原版弹独立 GroupEditDialog (含封面/排序/刷新字段),
 *   简化为对话框内嵌 [AppOutlinedTextField] 仅改分组名 (桌面端最小可用)
 * - 特殊分组 (groupId <= 0, 如 IdAll/IdLocal/IdUngrouped/IdError) 仅显示, 不可编辑/删除
 *
 * # 样式
 *
 * - 复用 sharedUiMain 既有组件: [DialogTitleBar] / [AppTextButton] / [AppOutlinedTextField] /
 *   [AppAlertDialog], 颜色走 [AppTheme.colors] (accent 默认 arcoblue-6 #165DFF, 与 app 端一致)
 * - 字符串/Painter 走 [rememberString]/[rememberPainter] (key-based, 跨平台)
 *
 * @param groups 分组列表 (按 order 排序, 调用方负责订阅 flowAll)
 * @param onAddGroup 新建分组回调, 参数为分组名
 * @param onRenameGroup 重命名回调, 参数为 groupId(Int) + 新分组名
 * @param onDeleteGroup 删除回调, 参数为 groupId(Int)
 * @param onDismiss 关闭对话框回调
 */
@Composable
fun GroupManageDialog(
    groups: List<BookGroup>,
    onAddGroup: (String) -> Unit,
    onRenameGroup: (Int, String) -> Unit,
    onDeleteGroup: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    // 编辑态: null=列表态, 非 null=正在编辑该分组 (groupId=0 表示新建)
    var editingGroupId by remember { mutableStateOf<Long?>(null) }
    var editingName by remember { mutableStateOf("") }
    // 删除确认态: 待删除的分组 (非空时弹出二次确认 alert)
    var deletingGroup by remember { mutableStateOf<BookGroup?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column {
                DialogTitleBar(
                    title = rememberString("group_manage"),
                    onBack = onDismiss,
                    actions = {
                        IconButton(onClick = {
                            editingGroupId = 0L // 0L 标记新建
                            editingName = ""
                        }) {
                            Icon(
                                painter = rememberPainter("ic_add"),
                                contentDescription = rememberString("add"),
                                tint = colors.primaryText,
                            )
                        }
                    },
                )

                // 编辑态: 显示输入框 + 确定/取消; 列表态: 显示分组列表
                if (editingGroupId != null) {
                    Column(Modifier.padding(16.dp)) {
                        AppOutlinedTextField(
                            value = editingName,
                            onValueChange = { editingName = it },
                            label = rememberString("group_name"),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            AppTextButton(text = rememberString("cancel")) {
                                editingGroupId = null
                                editingName = ""
                            }
                            Spacer(Modifier.width(8.dp))
                            AppTextButton(
                                text = rememberString("ok"),
                                enabled = editingName.isNotBlank(),
                            ) {
                                val name = editingName.trim()
                                val gid = editingGroupId
                                if (gid == 0L) {
                                    onAddGroup(name)
                                } else if (gid != null) {
                                    onRenameGroup(gid.toInt(), name)
                                }
                                editingGroupId = null
                                editingName = ""
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                    ) {
                        items(items = groups, key = { it.groupId }) { item ->
                            GroupItem(
                                item = item,
                                onRename = {
                                    editingGroupId = item.groupId
                                    editingName = item.groupName
                                },
                                onDelete = { deletingGroup = item },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        AppTextButton(text = rememberString("ok"), onClick = onDismiss)
                    }
                }
            }
        }
    }

    // 删除二次确认 (对照 app 端 GroupEditDialog.alert(R.string.delete, R.string.sure_del))
    deletingGroup?.let { group ->
        AppAlertDialog(
            onDismissRequest = { deletingGroup = null },
            title = rememberString("delete"),
            message = rememberString("sure_del"),
            okButton = AlertButton(
                text = rememberString("ok"),
                onClick = {
                    onDeleteGroup(group.groupId.toInt())
                    deletingGroup = null
                },
            ),
            cancelButton = AlertButton(
                text = rememberString("cancel"),
            ),
        )
    }
}

/**
 * 分组列表项 (对照 app 端 GroupManageDialog.GroupItem)。
 *
 * 单行: 分组名 (weight) + 编辑 + 删除。
 * 特殊分组 (groupId <= 0, 如全部/本地/未分组/出错) 仅显示名称, 不提供编辑/删除入口
 * (对照 app 端 GroupEditDialog 删除按钮 `groupId > 0` 守卫)。
 */
@Composable
private fun GroupItem(
    item: BookGroup,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    val editable = item.groupId > 0
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.groupName,
            color = colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (editable) {
            Text(
                text = rememberString("edit"),
                color = colors.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onRename)
                    .padding(8.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = rememberString("delete"),
                color = colors.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(8.dp),
            )
        }
    }
}
