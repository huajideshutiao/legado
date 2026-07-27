package io.legado.app.ui.book.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.book.getManageName
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import sh.calvin.reorderable.ReorderableCollectionItemScope

/**
 * 书籍分组管理
 */
class GroupManageDialog : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    private val viewModel: GroupViewModel by viewModels()

    // 拖拽期间以本地副本为准，flow 落库回推后再同步
    private var groups by mutableStateOf<List<BookGroup>>(emptyList())

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("书籍分组管理界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                groups = it
            }
        }
        RuleManageScaffold(
            items = groups,
            itemKey = { it.groupId },
            onMove = { from, to ->
                groups = groups.toMutableList().apply { add(to, removeAt(from)) }
            },
            titleBar = {
                DialogTitleBar(
                    title = getString(R.string.group_manage),
                    onBack = { dismissAllowingStateLoss() },
                    actions = {
                        IconButton(onClick = { addGroup() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = getString(R.string.add_group),
                                tint = AppTheme.colors.primaryText,
                            )
                        }
                    },
                )
            },
            actionBar = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = stringResource(R.string.ok)) {
                        dismissAllowingStateLoss()
                    }
                }
            },
        ) { item ->
            GroupItem(item)
        }
    }

    @Composable
    private fun ReorderableCollectionItemScope.GroupItem(item: BookGroup) {
        val colors = AppTheme.colors
        Row(
            Modifier
                .fillMaxWidth()
                .longPressDraggableHandle(onDragStopped = { persistOrder() })
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.getManageName(requireContext()),
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AppSwitch(
                checked = item.show,
                onCheckedChange = { viewModel.upGroup(item.copy(show = it)) },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.edit),
                color = colors.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable { showDialogFragment(GroupEditDialog(item)) }
                    .padding(8.dp),
            )
        }
    }

    private fun persistOrder() {
        groups.forEachIndexed { index, item -> item.order = index + 1 }
        viewModel.upGroup(*groups.toTypedArray())
    }

    private fun addGroup() {
        if (runBlocking { appDb.bookGroupDao.canAddGroup() }) {
            showDialogFragment(GroupEditDialog())
        } else {
            toastOnUi("分组已达上限(64个)")
        }
    }
}
