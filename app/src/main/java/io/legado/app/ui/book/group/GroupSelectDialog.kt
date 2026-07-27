package io.legado.app.ui.book.group

import android.os.Bundle
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
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.flow.conflate
import sh.calvin.reorderable.ReorderableCollectionItemScope


class GroupSelectDialog() : BaseComposeDialogFragment() {

    constructor(groupId: Long, requestCode: Int = -1) : this() {
        arguments = Bundle().apply {
            putLong("groupId", groupId)
            putInt("requestCode", requestCode)
        }
    }

    private var requestCode: Int = -1
    private val viewModel: GroupViewModel by viewModels()
    private val callBack get() = (activity as? CallBack)

    // 位掩码：勾选状态叠加进 groupId
    private var groupId by mutableStateOf(0L)
    private var groups by mutableStateOf<List<BookGroup>>(emptyList())


    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            arguments?.let {
                groupId = it.getLong("groupId")
                requestCode = it.getInt("requestCode", -1)
            }
            appDb.bookGroupDao.flowSelect().conflate().collect {
                groups = it
            }
        }
        RuleManageScaffold(
            items = groups,
            itemKey = { it.groupId },
            fillMaxHeight = false,
            onMove = { from, to ->
                groups = groups.toMutableList().apply { add(to, removeAt(from)) }
            },
            titleBar = {
                DialogTitleBar(
                    title = getString(R.string.group_select),
                    onBack = { dismissAllowingStateLoss() },
                    actions = {
                        IconButton(onClick = { showDialogFragment(GroupEditDialog()) }) {
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
                    AppTextButton(
                        text = stringResource(R.string.cancel),
                        color = AppTheme.colors.secondaryText,
                        onClick = { dismissAllowingStateLoss() },
                    )
                    AppTextButton(text = stringResource(R.string.ok)) {
                        callBack?.upGroup(requestCode, groupId)
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
        val checked = (groupId and item.groupId) > 0
        Row(
            Modifier
                .fillMaxWidth()
                .longPressDraggableHandle(onDragStopped = { persistOrder() })
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppCheckbox(
                checked = checked,
                onCheckedChange = {
                    groupId = if (it) groupId + item.groupId else groupId - item.groupId
                },
            )
            Text(
                text = item.groupName,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        groupId = if (checked) groupId - item.groupId else groupId + item.groupId
                    },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.edit),
                color = colors.primaryText,
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

    interface CallBack {
        fun upGroup(requestCode: Int, groupId: Long)
    }
}
