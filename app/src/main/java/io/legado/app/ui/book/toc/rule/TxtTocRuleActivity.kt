package io.legado.app.ui.book.toc.rule

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
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.ui.association.ImportTxtTocRuleDialog
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * TXT 目录规则管理页 (薄壳模式)。
 *
 * 实现 [TxtTocRuleUiActions] 接口供 shared 端 [TxtTocRuleScreen] 回调,
 * 已有同名方法直接 `override`, 其余方法以 `override fun onXxx() = xxx()`
 * 桥接现有方法, 不改动 Activity 内部其它调用点 (参考 SearchContentActivity 薄壳模式)。
 * 状态字段由 Activity 托管, [Content] 内打包为 [TxtTocRuleUiState] 传入
 * shared 端 [TxtTocRuleScreen]; 删除确认对话框由 shared 端用 AppAlertDialog 呈现,
 * 本类不再弹 alert (对照原 del/delSourceDialog 行为, 已下沉)。
 */
class TxtTocRuleActivity :
    BaseComposeActivity(),
    TxtTocRuleEditDialog.Callback,
    TxtTocRuleUiActions {

    private val viewModel by viewModels<TxtTocRuleViewModel>()
    private val importTocRuleKey = "tocRuleUrl"

    private var tocRules by mutableStateOf<List<TxtTocRule>>(emptyList())
    private val selected = mutableStateOf<Set<Long>>(emptySet())

    private val importDoc by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                showDialogFragment(ImportTxtTocRuleDialog(uri.toString()))
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
        initData()
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.txtTocRuleDao.observeAll().catch {
                AppLog.put("TXT目录规则界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { rules ->
                tocRules = rules
                selected.value = selected.value.intersect(rules.map { it.id }.toSet())
            }
        }
    }

    @Composable
    override fun Content() {
        val selectedSet by selected
        val state = TxtTocRuleUiState(
            tocRules = tocRules,
            selected = selectedSet,
        )
        TxtTocRuleScreen(state = state, actions = this)
    }

    private fun toggle(item: TxtTocRule, checked: Boolean) {
        selected.value = if (checked) selected.value + item.id else selected.value - item.id
    }

    private fun selection(): List<TxtTocRule> = tocRules.filter { selected.value.contains(it.id) }

    private fun persistOrder() {
        tocRules.forEachIndexed { index, item -> item.serialNumber = index + 1 }
        viewModel.update(*tocRules.toTypedArray())
    }

    private fun edit(source: TxtTocRule) {
        showDialogFragment(TxtTocRuleEditDialog(source.id))
    }

    override fun saveTxtTocRule(txtTocRule: TxtTocRule) {
        viewModel.save(txtTocRule)
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val defaultUrl = "https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json"
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importTocRuleKey)
            ?.splitNotBlank(",")
            ?.toMutableList()
            ?: mutableListOf()
        if (!cacheUrls.contains(defaultUrl)) {
            cacheUrls.add(0, defaultUrl)
        }
        alert(titleResource = R.string.import_on_line) {
            val getText = editTextView(
                hint = "url",
                filterValues = cacheUrls,
                onDelete = {
                    cacheUrls.remove(it)
                    aCache.put(importTocRuleKey, cacheUrls.joinToString(","))
                },
            )
            okButton {
                val text = getText()
                text.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importTocRuleKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportTxtTocRuleDialog(it))
                }
            }
            cancelButton()
        }
    }

    // ---- TxtTocRuleUiActions 实现 ----
    // 已有同名方法直接 override (无), 其余方法以 override fun onXxx() = xxx() 桥接现有方法

    override fun onBack() = finish()

    override fun onAddRule() {
        showDialogFragment(TxtTocRuleEditDialog())
    }

    override fun onEditRule(item: TxtTocRule) = edit(item)

    override fun onImportLocal() {
        importDoc.launch {
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("txt", "json")
        }
    }

    override fun onImportOnline() = showImportDialog()

    override fun onImportDefault() = viewModel.importDefault()

    override fun onHelp() = showHelp("txtTocRuleHelp")

    override fun onToggleSelect(item: TxtTocRule, checked: Boolean) = toggle(item, checked)

    override fun onSelectAll(all: Boolean) {
        selected.value = if (all) tocRules.map { it.id }.toSet() else emptySet()
    }

    override fun onRevertSelection() {
        selected.value = tocRules.map { it.id }.toSet() - selected.value
    }

    override fun onDelSelection() {
        viewModel.del(*selection().toTypedArray())
    }

    override fun onDel(item: TxtTocRule) = viewModel.del(item)

    override fun onEnableSelection(enabled: Boolean) {
        if (enabled) {
            viewModel.enableSelection(*selection().toTypedArray())
        } else {
            viewModel.disableSelection(*selection().toTypedArray())
        }
    }

    override fun onExportSelection() {
        exportResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "exportTxtTocRule.json",
                GSON.toJson(selection()).toByteArray(),
                "application/json"
            )
        }
    }

    override fun onMove(from: Int, to: Int) {
        tocRules = tocRules.toMutableList().apply { add(to, removeAt(from)) }
    }

    override fun onPersistOrder() = persistOrder()

    override fun onToTop(item: TxtTocRule) = viewModel.toTop(item)

    override fun onToBottom(item: TxtTocRule) = viewModel.toBottom(item)

    override fun onEnableRule(item: TxtTocRule, enabled: Boolean) {
        item.enable = enabled
        viewModel.update(item)
    }

}
