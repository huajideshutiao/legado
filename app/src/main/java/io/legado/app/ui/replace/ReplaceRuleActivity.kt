package io.legado.app.ui.replace

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import io.legado.app.ui.compose.component.AppDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.component.SelectActionBar
import io.legado.app.ui.compose.component.dragSelectable
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showExportSuccess
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableCollectionItemScope

/**
 * 替换规则管理
 */
class ReplaceRuleActivity : BaseComposeActivity() {
    private val viewModel by viewModels<ReplaceRuleViewModel>()
    private val importRecordKey = "replaceRuleRecordKey"
    private var groups by mutableStateOf<List<String>>(emptyList())
    private var replaceRuleFlowJob: Job? = null
    private var dataInit = false

    private var replaceRules by mutableStateOf<List<ReplaceRule>>(emptyList())
    private val selected = mutableStateOf<Set<Long>>(emptySet())
    private var searchKey by mutableStateOf("")

    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
            }
        }
    private val importDoc by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                showDialogFragment(ImportReplaceRuleDialog(uri.toString()))
            }
        }
    }
    private val exportResult by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                showExportSuccess(uri)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        observeReplaceRuleData()
        observeGroupData()
    }

    /** 分组筛选沿用原 SearchView 语义：设查询串并驱动数据流(no_group / group:xxx / 关键词)。 */
    private fun setQuery(query: String) {
        searchKey = query
        observeReplaceRuleData(query)
    }

    @Composable
    override fun Content() {
        val selectedSet by selected
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        RuleManageScaffold(
            items = replaceRules,
            itemKey = { it.id },
            onMove = { from, to ->
                replaceRules = replaceRules.toMutableList().apply { add(to, removeAt(from)) }
            },
            listState = listState,
            titleBar = {
                AppTitleBar(
                    title = stringResource(R.string.replace_purify),
                    onBack = { finish() },
                    titleContent = {
                        AppSearchField(
                            value = searchKey,
                            onValueChange = { setQuery(it) },
                            hint = stringResource(R.string.replace_purify_search),
                        )
                    },
                    actions = { ReplaceRuleActions() },
                )
            },
            listModifier = Modifier.dragSelectable(
                listState = listState,
                autoScrollScope = scope,
                isSelected = { index -> selected.value.contains(replaceRules[index].id) },
                onSelectedChanged = { index, sel -> toggle(replaceRules[index], sel) },
            ),
            actionBar = {
                SelectActionBar(
                    selectCount = selectedSet.size,
                    allCount = replaceRules.size,
                    onSelectAll = { all ->
                        selected.value = if (all) replaceRules.map { it.id }.toSet() else emptySet()
                    },
                    onRevertSelection = {
                        selected.value = replaceRules.map { it.id }.toSet() - selectedSet
                    },
                    mainActionText = stringResource(R.string.delete),
                    onMainAction = {
                        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
                            yesButton { viewModel.delSelection(selection()) }
                            noButton()
                        }
                    },
                    actions = listOf(
                        SelectAction(getString(R.string.enable_selection)) {
                            viewModel.enableSelection(selection())
                        },
                        SelectAction(getString(R.string.disable_selection)) {
                            viewModel.disableSelection(selection())
                        },
                        SelectAction(getString(R.string.selection_to_top)) {
                            viewModel.topSelect(selection())
                        },
                        SelectAction(getString(R.string.selection_to_bottom)) {
                            viewModel.bottomSelect(selection())
                        },
                        SelectAction(getString(R.string.export_selection)) {
                            exportResult.launch {
                                mode = HandleFileContract.EXPORT
                                fileData = HandleFileContract.FileData(
                                    "exportReplaceRule.json",
                                    GSON.toJson(selection()).toByteArray(),
                                    "application/json"
                                )
                            }
                        },
                    ),
                )
            },
        ) { item ->
            ReplaceRuleItem(item, selectedSet.contains(item.id))
        }
    }

    @Composable
    private fun ReplaceRuleActions() {
        val colors = AppTheme.colors
        var showGroup by remember { mutableStateOf(false) }
        // 分组筛选：ic_groups 图标 + 下拉(分组管理/无分组/各分组)
        Box {
            IconButton(onClick = { showGroup = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_groups),
                    contentDescription = stringResource(R.string.menu_action_group),
                    tint = colors.primaryText,
                )
            }
            AppDropdownMenu(expanded = showGroup, onDismissRequest = { showGroup = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_manage), color = colors.primaryText) },
                    onClick = { showGroup = false; showDialogFragment<GroupManageDialog>() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.no_group), color = colors.primaryText) },
                    onClick = { showGroup = false; setQuery(getString(R.string.no_group)) },
                )
                groups.forEach { group ->
                    DropdownMenuItem(
                        text = { Text(group, color = colors.primaryText) },
                        onClick = { showGroup = false; setQuery("group:$group") },
                    )
                }
            }
        }
        OverflowMenu { dismiss ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_replace_rule), color = colors.primaryText) },
                onClick = {
                    dismiss()
                    editActivity.launch(ReplaceEditActivity.startIntent(this@ReplaceRuleActivity))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_local), color = colors.primaryText) },
                onClick = {
                    dismiss()
                    importDoc.launch {
                        mode = HandleFileContract.FILE
                        allowExtensions = arrayOf("txt", "json")
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_on_line), color = colors.primaryText) },
                onClick = { dismiss(); showImportDialog() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.help), color = colors.primaryText) },
                onClick = { dismiss(); showHelp("replaceRuleHelp") },
            )
        }
    }

    @Composable
    private fun ReorderableCollectionItemScope.ReplaceRuleItem(
        item: ReplaceRule,
        checked: Boolean,
    ) {
        val colors = AppTheme.colors
        var showMenu by remember { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .longPressDraggableHandle(onDragStopped = { persistOrder() })
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppCheckbox(
                checked = checked,
                onCheckedChange = { toggle(item, it) },
            )
            Text(
                text = item.getDisplayNameGroup(),
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { toggle(item, !checked) },
            )
            AppSwitch(
                checked = item.isEnabled,
                onCheckedChange = { viewModel.update(item.copy(isEnabled = it)) },
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { edit(item) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.edit),
                    tint = colors.primaryText,
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more_menu),
                        tint = colors.primaryText,
                        modifier = Modifier.size(24.dp),
                    )
                }
                AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.to_top), color = colors.primaryText) },
                        onClick = { showMenu = false; viewModel.toTop(item); setResult(RESULT_OK) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.to_bottom), color = colors.primaryText) },
                        onClick = { showMenu = false; viewModel.toBottom(item); setResult(RESULT_OK) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = colors.primaryText) },
                        onClick = { showMenu = false; delete(item) },
                    )
                }
            }
        }
    }

    private fun toggle(item: ReplaceRule, checked: Boolean) {
        selected.value = if (checked) selected.value + item.id else selected.value - item.id
    }

    private fun selection(): List<ReplaceRule> = replaceRules.filter { selected.value.contains(it.id) }

    private fun persistOrder() {
        setResult(RESULT_OK)
        replaceRules.forEachIndexed { index, item -> item.order = index + 1 }
        viewModel.update(*replaceRules.toTypedArray())
    }

    private fun edit(rule: ReplaceRule) {
        setResult(RESULT_OK)
        editActivity.launch(ReplaceEditActivity.startIntent(this, rule.id))
    }

    private fun delete(rule: ReplaceRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rule.name)
            noButton()
            yesButton {
                setResult(RESULT_OK)
                viewModel.delete(rule)
            }
        }
    }

    private fun observeReplaceRuleData(searchKey: String? = null) {
        dataInit = false
        replaceRuleFlowJob?.cancel()
        replaceRuleFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> {
                    appDb.replaceRuleDao.flowAll()
                }

                searchKey == getString(R.string.no_group) -> {
                    appDb.replaceRuleDao.flowNoGroup()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.replaceRuleDao.flowGroupSearch("%$key%")
                }

                else -> {
                    appDb.replaceRuleDao.flowSearch("%$searchKey%")
                }
            }.catch {
                AppLog.put("替换规则管理界面更新数据出错", it)
            }.flowOn(IO).conflate().collect {
                if (dataInit) {
                    setResult(RESULT_OK)
                }
                replaceRules = it
                selected.value = selected.value.intersect(it.map { r -> r.id }.toSet())
                dataInit = true
            }
        }
    }

    private fun observeGroupData() {
        lifecycleScope.launch {
            appDb.replaceRuleDao.flowGroups().collect {
                groups = it
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(titleResource = R.string.import_on_line) {
            val getText = editTextView(
                hint = "url",
                filterValues = cacheUrls,
                onDelete = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                },
            )
            okButton {
                val text = getText()
                text.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(
                        ImportReplaceRuleDialog(it)
                    )
                }
            }
            cancelButton()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Coroutine.async { ContentProcessor.upReplaceRules() }
    }
}
