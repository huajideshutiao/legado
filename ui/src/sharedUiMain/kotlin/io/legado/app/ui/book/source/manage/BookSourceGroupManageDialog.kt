package io.legado.app.ui.book.source.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.AppDbProviders
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDialogScaffold
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.add
import legado.ui.generated.resources.add_group
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.delete
import legado.ui.generated.resources.edit
import legado.ui.generated.resources.group_edit
import legado.ui.generated.resources.group_manage
import legado.ui.generated.resources.group_name
import legado.ui.generated.resources.ic_add
import legado.ui.generated.resources.ok
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 书源分组管理对话框 (对照 app 端 book/source/manage/GroupManageDialog)。
 *
 * 管理 `BookSource.bookSourceGroup` (逗号分隔字符串标签): 全部分组列表,
 * 每项可改名/删除, 标题栏添加入口。增删改走 shared
 * [BookSourceViewModelShared] (addGroup/upGroup/delGroup 已下沉 commonMain),
 * 分组列表订阅 `bookSourceDao.flowGroups()` (live Flow, DB 变更自动刷新)。
 *
 * 新建/改名按原版是独立 alert 子弹窗 (标题"添加分组"/"编辑分组" + 输入框 + 确定/取消),
 * 不带顶栏与返回箭头; 列表态才是带顶栏的主弹窗。
 *
 * 与书架分组 ([BookGroup]) 无关, 勿与 book/group/GroupManageDialog 混用。
 *
 * @param onDismiss 关闭回调
 */
@Composable
fun BookSourceGroupManageDialog(onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val viewModel = remember(scope) { BookSourceViewModelShared(scope) }
    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        AppDbProviders.get().bookSourceDao.flowGroups().collect { groups = it }
    }
    // null=无编辑弹窗, (null, "")=新建, (oldName, value)=改名
    var editing by remember { mutableStateOf<Pair<String?, String>?>(null) }

    AppDialogScaffold(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.group_manage),
        properties = AppDialogSizes.properties(),
        titleActions = {
            IconButton(onClick = { editing = null to "" }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_add),
                    contentDescription = stringResource(Res.string.add),
                    tint = colors.primaryText,
                )
            }
        },
        contentPadding = PaddingValues(0.dp),
    ) {
        LazyColumn(Modifier.heightIn(max = AppDialogSizes.textAreaMaxHeight())) {
            items(items = groups, key = { it }) { group ->
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingMd),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = group,
                        color = colors.primaryText,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(Res.string.edit),
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { editing = group to group }
                            .padding(DesignTokens.spacingDefault),
                    )
                    Text(
                        text = stringResource(Res.string.delete),
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { viewModel.delGroup(group) }
                            .padding(DesignTokens.spacingDefault),
                    )
                }
            }
        }
    }

    // 新建/改名子弹窗 (对照原版 addGroup/editGroup 的 alert: 标题 + 输入框 + 确定/取消, 无顶栏)
    editing?.let { (old, value) ->
        AppAlertDialog(
            onDismissRequest = { editing = null },
            title = stringResource(
                if (old == null) Res.string.add_group else Res.string.group_edit
            ),
            okButton = AlertButton(
                text = stringResource(Res.string.ok),
                enabled = value.isNotBlank(),
            ) {
                val name = value.trim()
                // 对照 app 端 addGroup: 新分组名给所有未分组书源 (空串时 app 端不调用)
                if (old == null) viewModel.addGroup(name) else viewModel.upGroup(old, name)
                editing = null
            },
            cancelButton = AlertButton(text = stringResource(Res.string.cancel)) { editing = null },
        ) {
            AppUnderlineTextField(
                value = value,
                onValueChange = { editing = old to it },
                label = stringResource(Res.string.group_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
