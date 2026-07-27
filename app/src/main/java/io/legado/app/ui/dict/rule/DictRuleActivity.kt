package io.legado.app.ui.dict.rule

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
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showExportSuccess
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.toJson
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 字典规则管理页宿主 (薄壳)。
 *
 * 实现 [DictRuleUiActions] 接口供下沉到 shared/sharedUiMain 的 [DictRuleScreen] 回调,
 * 平台依赖 (appDb/showDialogFragment/HandleFileContract/ACache/showHelp 等) 均在此桥接;
 * 展示状态打包为 [DictRuleUiState] 传入 [DictRuleScreen]。
 */
class DictRuleActivity : BaseComposeActivity(), DictRuleUiActions {

    private val viewModel by viewModels<DictRuleViewModel>()
    private val importRecordKey = "dictRuleUrls"

    private var dictRules by mutableStateOf<List<DictRule>>(emptyList())
    private val selected = mutableStateOf<Set<String>>(emptySet())

    private val importDoc by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                showDialogFragment(ImportDictRuleDialog(uri.toString()))
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
        observeDictRuleData()
    }

    private fun observeDictRuleData() {
        lifecycleScope.launch {
            appDb.dictRuleDao.flowAll().catch {
                AppLog.put("字典规则获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                dictRules = it
                // 落库回推后清理已不存在的选中项
                selected.value = selected.value.intersect(it.map { r -> r.name }.toSet())
            }
        }
    }

    @Composable
    override fun Content() {
        val state = DictRuleUiState(
            dictRules = dictRules,
            selected = selected.value,
        )
        DictRuleScreen(state = state, actions = this)
    }

    // ===== DictRuleUiActions 桥接 =====

    override fun onBack() = finish()

    override fun onAddRule() {
        showDialogFragment<DictRuleEditDialog>()
    }

    override fun onEditRule(name: String) {
        showDialogFragment(DictRuleEditDialog(name))
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

    override fun onImportDefault() {
        viewModel.importDefault()
    }

    override fun onHelp() {
        showHelp("dictRuleHelp")
    }

    override fun onToggleSelected(item: DictRule, checked: Boolean) {
        selected.value = if (checked) selected.value + item.name else selected.value - item.name
    }

    override fun onSelectAll(all: Boolean) {
        selected.value = if (all) dictRules.map { it.name }.toSet() else emptySet()
    }

    override fun onRevertSelection() {
        selected.value = dictRules.map { it.name }.toSet() - selected.value
    }

    override fun onMoveItem(from: Int, to: Int) {
        dictRules = dictRules.toMutableList().apply { add(to, removeAt(from)) }
    }

    override fun onPersistOrder() {
        persistOrder()
    }

    override fun onUpdateRuleEnabled(item: DictRule, enabled: Boolean) {
        viewModel.update(item.copy(enabled = enabled))
    }

    override fun onDeleteRule(rule: DictRule) {
        viewModel.delete(rule)
    }

    override fun onDeleteSelection() {
        viewModel.delete(*selection().toTypedArray())
    }

    override fun onEnableSelection() {
        viewModel.enableSelection(*selection().toTypedArray())
    }

    override fun onDisableSelection() {
        viewModel.disableSelection(*selection().toTypedArray())
    }

    override fun onExportSelection() {
        exportResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "exportDictRule.json",
                GSON.toJson(selection()).toByteArray(),
                "application/json"
            )
        }
    }

    // ===== 内部辅助 =====

    private fun selection(): List<DictRule> = dictRules.filter { selected.value.contains(it.name) }

    private fun persistOrder() {
        dictRules.forEachIndexed { index, item -> item.sortNumber = index + 1 }
        viewModel.update(*dictRules.toTypedArray())
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
                        ImportDictRuleDialog(it)
                    )
                }
            }
            cancelButton()
        }
    }
}
