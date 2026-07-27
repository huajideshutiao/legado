package io.legado.app.ui.replace

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.reorderable.RuleItemScope
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.requestInputMethod
import kotlinx.coroutines.flow.conflate

/**
 * 替换规则分组管理:纯分组列表(无排序/多选),支持新增/改名/删除。
 */
class GroupManageDialog : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    private val viewModel: ReplaceRuleViewModel by activityViewModels()

    private var groups by mutableStateOf<List<String>>(emptyList())

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            appDb.replaceRuleDao.flowGroups().conflate().collect {
                groups = it
            }
        }
        RuleManageScaffold(
            items = groups,
            itemKey = { it },
            onMove = { _, _ -> },
            titleBar = {
                DialogTitleBar(
                    title = getString(R.string.group_manage),
                    onBack = { dismissAllowingStateLoss() },
                    actions = {
                        IconButton(onClick = { addGroup() }) {
                            Icon(
                                painter = rememberPainter("ic_add"),
                                contentDescription = getString(R.string.add_group),
                                tint = AppTheme.colors.primaryText,
                            )
                        }
                    },
                )
            },
        ) { item ->
            GroupItem(item)
        }
    }

    @Composable
    private fun RuleItemScope.GroupItem(item: String) {
        val colors = AppTheme.colors
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AppTextButton(text = stringResource(R.string.edit)) { editGroup(item) }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { viewModel.delGroup(item) }) {
                Icon(
                    painter = rememberPainter("ic_clear_all"),
                    contentDescription = stringResource(R.string.delete),
                    tint = colors.primaryText,
                )
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun addGroup() {
        alert(title = getString(R.string.add_group)) {
            val getText = editTextView(hint = getString(R.string.group_name), autoFocus = true)
            okButton {
                getText().let {
                    if (it.isNotBlank()) {
                        viewModel.addGroup(it)
                    }
                }
            }
            cancelButton()
        }.requestInputMethod()
    }

    @SuppressLint("InflateParams")
    private fun editGroup(group: String) {
        alert(title = getString(R.string.group_edit)) {
            val getText = editTextView(
                hint = getString(R.string.group_name),
                text = group,
                autoFocus = true,
            )
            okButton {
                viewModel.upGroup(group, getText())
            }
            cancelButton()
        }.requestInputMethod()
    }
}
