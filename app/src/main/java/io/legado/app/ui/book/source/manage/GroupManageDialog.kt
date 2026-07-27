package io.legado.app.ui.book.source.manage

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.appDb
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.requestInputMethod
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import kotlinx.coroutines.flow.conflate

/**
 * 书源分组管理
 */
class GroupManageDialog : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    private val viewModel: BookSourceViewModel by activityViewModels()

    private var groups by mutableStateOf<List<String>>(emptyList())

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            appDb.bookSourceDao.flowGroups().conflate().collect {
                groups = it
            }
        }
        Column(Modifier.fillMaxWidth()) {
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
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(groups, key = { it }) { item ->
                    GroupItem(item)
                }
            }
        }
    }

    @Composable
    private fun GroupItem(item: String) {
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
