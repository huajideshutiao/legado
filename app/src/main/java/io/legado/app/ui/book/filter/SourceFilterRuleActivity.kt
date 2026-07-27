package io.legado.app.ui.book.filter

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.ui.association.ImportSourceFilterRuleDialog
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showExportSuccess
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.toJson
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 搜索结果屏蔽规则管理。
 *
 * 薄壳: UI 由 shared 模块 [SourceFilterRuleScreen] 渲染, 本类只持有 state
 * (filterRules/selected/searchKey) 并实现 [SourceFilterRuleUiActions] 桥接
 * appDb / ViewModel / showDialogFragment / alert 等平台依赖。
 */
class SourceFilterRuleActivity :
    BaseComposeActivity(),
    SourceFilterEditDialog.Callback,
    SourceFilterRuleUiActions {

    private val viewModel by viewModels<SourceFilterRuleViewModel>()

    private val importRecordKey = "sourceFilterRuleRecordKey"
    private var flowJob: Job? = null
    private var dataInit = false

    private var filterRules by mutableStateOf<List<SourceFilterRule>>(emptyList())
    private val selected = mutableStateOf<Set<String>>(emptySet())
    private var searchKey by mutableStateOf("")

    private val importDoc by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                showDialogFragment(ImportSourceFilterRuleDialog(uri.toString()))
            }
        }
    }
    private val exportResult by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri -> showExportSuccess(uri) }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        observe()
    }

    @Composable
    override fun Content() {
        val selectedSet by selected
        SourceFilterRuleScreen(
            state = SourceFilterRuleUiState(
                rules = filterRules,
                selected = selectedSet,
                searchKey = searchKey,
            ),
            actions = this,
        )
    }

    // ===== SourceFilterRuleUiActions 实现 =====

    override fun onBack() = finish()

    override fun onSearchKeyChange(key: String) {
        searchKey = key
        observe(key)
    }

    override fun onToggleSelected(item: SourceFilterRule, checked: Boolean) {
        selected.value = if (checked) selected.value + item.id else selected.value - item.id
    }

    override fun onSelectAll(all: Boolean) {
        selected.value = if (all) filterRules.map { it.id }.toSet() else emptySet()
    }

    override fun onRevertSelection() {
        selected.value = filterRules.map { it.id }.toSet() - selected.value
    }

    override fun onMoveItem(from: Int, to: Int) {
        filterRules = filterRules.toMutableList().apply { add(to, removeAt(from)) }
    }

    override fun onPersistOrder() {
        filterRules.forEachIndexed { index, item -> item.order = index + 1 }
        viewModel.update(*filterRules.toTypedArray())
    }

    override fun onDeleteSelection() {
        viewModel.delSelection(selection())
    }

    override fun onDeleteRule(rule: SourceFilterRule) {
        viewModel.delete(rule)
    }

    override fun onDeleteAll() {
        lifecycleScope.launch(IO) {
            appDb.sourceFilterRuleDao.deleteAll()
            SearchBookFilter.reload()
        }
    }

    override fun onEnableSelection() {
        viewModel.enableSelection(selection())
    }

    override fun onDisableSelection() {
        viewModel.disableSelection(selection())
    }

    override fun onTopSelect() {
        viewModel.topSelect(selection())
    }

    override fun onBottomSelect() {
        viewModel.bottomSelect(selection())
    }

    override fun onExportSelection() {
        exportResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "exportSourceFilterRule.json",
                GSON.toJson(selection()).toByteArray(),
                "application/json"
            )
        }
    }

    override fun onEditRule(rule: SourceFilterRule) {
        showDialogFragment(SourceFilterEditDialog(existing = rule))
    }

    override fun onToTop(rule: SourceFilterRule) {
        viewModel.toTop(rule)
    }

    override fun onToBottom(rule: SourceFilterRule) {
        viewModel.toBottom(rule)
    }

    override fun onToggleEnabled(rule: SourceFilterRule, enabled: Boolean) {
        viewModel.update(rule.copy(enabled = enabled))
    }

    override fun onAddRule() {
        showDialogFragment(SourceFilterEditDialog(existing = null))
    }

    override fun onImportLocal() {
        importDoc.launch {
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("txt", "json")
        }
    }

    override fun onImportOnline() {
        showImportDialog()
    }

    // ===== 私有辅助 =====

    private fun selection(): List<SourceFilterRule> =
        filterRules.filter { selected.value.contains(it.id) }

    private fun observe(searchKey: String? = null) {
        dataInit = false
        flowJob?.cancel()
        flowJob = lifecycleScope.launch {
            if (searchKey.isNullOrEmpty()) {
                appDb.sourceFilterRuleDao.flowAll()
            } else {
                appDb.sourceFilterRuleDao.flowSearch("%$searchKey%")
            }.catch {
                AppLog.put("过滤规则管理界面更新数据出错", it)
            }.flowOn(IO).conflate().collect { rules ->
                if (dataInit) setResult(RESULT_OK)
                filterRules = rules
                selected.value = selected.value.intersect(rules.map { it.id }.toSet())
                dataInit = true
                delay(100)
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
                    showDialogFragment(ImportSourceFilterRuleDialog(it))
                }
            }
            cancelButton()
        }
    }

    override fun onSourceFilterRuleSave(rule: SourceFilterRule, isNew: Boolean) {
        lifecycleScope.launch(IO) {
            SearchBookFilter.save(rule, isNew)
        }
    }
}
