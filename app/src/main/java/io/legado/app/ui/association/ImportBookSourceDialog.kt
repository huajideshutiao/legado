package io.legado.app.ui.association

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import io.legado.app.ui.compose.component.AppDropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
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
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.compose.platform.rememberPainter
import kotlinx.coroutines.runBlocking
import io.legado.app.ui.compose.component.AppAutoCompleteField
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.showDialogFragment
import org.jetbrains.compose.resources.getString

/**
 * 导入书源弹出窗口
 */
class ImportBookSourceDialog() : BaseComposeDialogFragment(), CodeDialog.Callback {


    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val viewModel by viewModels<ImportBookSourceViewModel>()
    private var version by mutableIntStateOf(0)

    // 分组菜单项文案随选择变化，复刻原 menu_new_group 的 item.title 动态更新
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
            viewModel.errorLiveData.observe(this@ImportBookSourceDialog) {
                loading = false
                error = it
            }
            viewModel.successLiveData.observe(this@ImportBookSourceDialog) {
                loading = false
                if (it > 0) version++ else error = getString(R.string.wrong_format)
            }
            val source = arguments?.getString("source")
            if (source.isNullOrEmpty()) dismiss() else viewModel.importSource(source)
        }

        ImportListScaffold(
            title = getString(R.string.import_book_source),
            loading = loading,
            errorText = error,
            itemCount = viewModel.allSources.size,
            selectCount = viewModel.selectCount,
            isSelectAll = viewModel.isSelectAll,
            itemLabel = { viewModel.allSources[it].bookSourceName },
            itemState = {
                val local = viewModel.checkSources[it]
                when {
                    local == null -> "新增"
                    viewModel.allSources[it].lastUpdateTime > local.lastUpdateTime -> "更新"
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
            titleActions = { TitleMenu() },
        )
    }

    @Composable
    private fun RowScope.TitleMenu() {
        val colors = AppTheme.colors
        var showOverflow by remember { mutableStateOf(false) }
        // menu_new_group：原为常显文本项，文案随分组选择变化
        Text(
            text = groupTitle,
            color = colors.primaryText,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .clickable { alertCustomGroup() }
                .padding(horizontal = 8.dp),
        )
        Box {
            IconButton(onClick = { showOverflow = true }) {
                Icon(
                    painter = rememberPainter("ic_more_vert"),
                    contentDescription = null,
                    tint = colors.primaryText,
                )
            }
            AppDropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                DropdownMenuItem(
                    onClick = {
                        showOverflow = false
                        selectNew()
                    },
                ) { Text(getString(R.string.select_new_source)) }
                DropdownMenuItem(
                    onClick = {
                        showOverflow = false
                        selectUpdate()
                    },
                ) { Text(getString(R.string.select_update_source)) }
                CheckableMenuItem(getString(R.string.keep_original_name), AppConfig.importKeepName) {
                    AppConfig.importKeepName = it
                    version++
                }
                CheckableMenuItem(getString(R.string.keep_group), AppConfig.importKeepGroup) {
                    AppConfig.importKeepGroup = it
                    version++
                }
                CheckableMenuItem(getString(R.string.keep_enable), AppConfig.importKeepEnable) {
                    AppConfig.importKeepEnable = it
                    version++
                }
            }
        }
    }

    @Composable
    private fun CheckableMenuItem(text: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        DropdownMenuItem(onClick = { onToggle(!checked) }) {
            Text(text, Modifier
                .weight(1f)
                .padding(end = 12.dp))
            AppMenuCheckbox(checked = checked)
        }
    }

    private fun alertCustomGroup() {
        alert(R.string.diy_edit_source_group) {
            val groups = runBlocking { appDb.bookSourceDao.allGroups() }.toList()
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
            cancelButton()
        }
    }

    private fun selectNew() {
        val selectAllNew = viewModel.isSelectAllNew
        viewModel.newSourceStatus.forEachIndexed { index, b ->
            if (b) viewModel.selectStatus[index] = !selectAllNew
        }
        version++
    }

    private fun selectUpdate() {
        val selectAllUpdate = viewModel.isSelectAllUpdate
        viewModel.updateSourceStatus.forEachIndexed { index, b ->
            if (b) viewModel.selectStatus[index] = !selectAllUpdate
        }
        version++
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
                GSON.toJson(viewModel.allSources[index]),
                disableEdit = false,
                requestId = index.toString()
            )
        )
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toInt()?.let {
            GSON.fromJsonObject<BookSource>(code).getOrNull()?.let { source ->
                viewModel.allSources[it] = source
                version++
            }
        }
    }
}
