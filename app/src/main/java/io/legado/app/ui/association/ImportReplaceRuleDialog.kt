package io.legado.app.ui.association

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.compose.component.AppAutoCompleteField
import kotlinx.coroutines.runBlocking
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.showDialogFragment

class ImportReplaceRuleDialog() : BaseComposeDialogFragment(), CodeDialog.Callback {


    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val viewModel by viewModels<ImportReplaceRuleViewModel>()
    private var version by mutableIntStateOf(0)
    private var groupTitle by mutableStateOf("")

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    @Composable
    override fun Content() {
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        if (groupTitle.isEmpty()) groupTitle = getString(R.string.diy_source_group)
        version

        LaunchedEffect(Unit) {
            viewModel.errorLiveData.observe(this@ImportReplaceRuleDialog) {
                loading = false
                error = it
            }
            viewModel.successLiveData.observe(this@ImportReplaceRuleDialog) {
                loading = false
                if (it > 0) version++ else error = getString(R.string.wrong_format)
            }
            val source = arguments?.getString("source")
            if (source.isNullOrEmpty()) dismiss() else viewModel.import(source)
        }

        ImportListScaffold(
            title = getString(R.string.import_replace_rule),
            loading = loading,
            errorText = error,
            itemCount = viewModel.allRules.size,
            selectCount = viewModel.selectCount,
            isSelectAll = viewModel.isSelectAll,
            itemLabel = {
                val item = viewModel.allRules[it]
                if (item.group.isNullOrBlank()) item.name else "${item.name}(${item.group})"
            },
            itemState = {
                val local = viewModel.checkRules[it]
                val item = viewModel.allRules[it]
                when {
                    local == null -> "新增"
                    item.pattern != local.pattern
                        || item.replacement != local.replacement
                        || item.isRegex != local.isRegex
                        || item.scope != local.scope -> "更新"

                    else -> "已有"
                }
            },
            itemChecked = { viewModel.selectStatus[it] },
            onItemChecked = { index, checked ->
                viewModel.selectStatus[index] = checked
                version++
            },
            onOpen = { openCode(it) },
            onToggleAll = { toggleAll() },
            onCancel = { dismissAllowingStateLoss() },
            onOk = { onImport() },
            titleActions = { GroupMenu() },
        )
    }

    @Composable
    private fun RowScope.GroupMenu() {
        Text(
            text = groupTitle,
            color = AppTheme.colors.primaryText,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .clickable { alertCustomGroup() }
                .padding(horizontal = 8.dp),
        )
    }

    private fun alertCustomGroup() {
        alert(R.string.diy_edit_source_group) {
            val groups = runBlocking { appDb.replaceRuleDao.allGroups() }.toList()
            val addGroup = mutableStateOf(false)
            val name = mutableStateOf("")
            customView {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(getString(R.string.add_group), color = AppTheme.colors.primaryText)
                            Text(
                                getString(R.string.custom_group_summary),
                                color = AppTheme.colors.secondaryText,
                            )
                        }
                        AppSwitch(checked = addGroup.value, onCheckedChange = { addGroup.value = it })
                    }
                    AppAutoCompleteField(
                        value = name.value,
                        onValueChange = { name.value = it },
                        label = getString(R.string.group_name),
                        values = groups,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            okButton {
                viewModel.isAddGroup = addGroup.value
                viewModel.groupName = name.value
                groupTitle = if (viewModel.groupName.isNullOrBlank()) {
                    getString(R.string.diy_source_group)
                } else {
                    val group = getString(R.string.diy_edit_source_group_title, viewModel.groupName)
                    if (viewModel.isAddGroup) "+$group" else group
                }
            }
            noButton()
        }
    }

    private fun toggleAll() {
        val selectAll = viewModel.isSelectAll
        viewModel.selectStatus.forEachIndexed { index, b ->
            if (b != !selectAll) viewModel.selectStatus[index] = !selectAll
        }
        version++
    }

    private fun onImport() {
        val waitDialog = WaitDialog.from(requireActivity())
        waitDialog.show(requireActivity().supportFragmentManager)
        viewModel.importSelect {
            waitDialog.dismissSafe()
            dismissAllowingStateLoss()
        }
    }

    private fun openCode(index: Int) {
        showDialogFragment(
            CodeDialog(
                GSON.toJson(viewModel.allRules[index]),
                disableEdit = false,
                requestId = index.toString()
            )
        )
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toInt()?.let {
            GSON.fromJsonObject<ReplaceRule>(code).getOrNull()?.let { rule ->
                viewModel.allRules[it] = rule
                version++
            }
        }
    }
}
