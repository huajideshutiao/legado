package io.legado.app.ui.book.source.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
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
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.group_manage
import legado.shared.generated.resources.group_name
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.ok
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
    // null=列表态, (null, "")=新建, (oldName, value)=编辑
    var editing by remember { mutableStateOf<Pair<String?, String>?>(null) }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column {
                DialogTitleBar(
                    title = stringResource(Res.string.group_manage),
                    onBack = onDismiss,
                    actions = {
                        IconButton(onClick = { editing = null to "" }) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_add),
                                contentDescription = stringResource(Res.string.add),
                                tint = colors.primaryText,
                            )
                        }
                    },
                )
                if (editing != null) {
                    Column(Modifier.padding(16.dp)) {
                        AppOutlinedTextField(
                            value = editing!!.second,
                            onValueChange = { editing = editing!!.first to it },
                            label = stringResource(Res.string.group_name),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            AppTextButton(text = stringResource(Res.string.cancel)) {
                                editing = null
                            }
                            AppTextButton(
                                text = stringResource(Res.string.ok),
                                enabled = editing!!.second.isNotBlank(),
                            ) {
                                val (old, new) = editing!!
                                val name = new.trim()
                                // 对照 app 端 addGroup: 新分组名给所有未分组书源 (空串时 app 端不调用)
                                if (old == null) viewModel.addGroup(name)
                                else viewModel.upGroup(old, name)
                                editing = null
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(items = groups, key = { it }) { group ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                                        .padding(8.dp),
                                )
                                Text(
                                    text = stringResource(Res.string.delete),
                                    color = colors.secondaryText,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable { viewModel.delGroup(group) }
                                        .padding(8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
