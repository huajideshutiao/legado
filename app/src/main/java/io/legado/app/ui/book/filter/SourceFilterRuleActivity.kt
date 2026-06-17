package io.legado.app.ui.book.filter

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.databinding.ActivityRecyclerWithActionBarBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.cancelButton
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.okButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.association.ImportSourceFilterRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showExportSuccess
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class SourceFilterRuleActivity :
    VMBaseActivity<ActivityRecyclerWithActionBarBinding, SourceFilterRuleViewModel>(),
    SearchView.OnQueryTextListener,
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    SourceFilterRuleAdapter.Callback,
    SourceFilterEditDialog.Callback {

    override val binding by viewBinding(ActivityRecyclerWithActionBarBinding::inflate)
    override val viewModel by viewModels<SourceFilterRuleViewModel>()

    private val importRecordKey = "sourceFilterRuleRecordKey"
    private val adapter by lazy { SourceFilterRuleAdapter(this, this) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var flowJob: Job? = null
    private var dataInit = false

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
        binding.titleBar.title = getString(R.string.source_filter_rule)
        initRecyclerView()
        initSearchView()
        initSelectActionView()
        observe()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.source_filter_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> showDialogFragment(SourceFilterEditDialog(existing = null))
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_del_all -> alert(R.string.delete_all) {
                setMessage(R.string.source_filter_rule_delete_all_confirm)
                yesButton {
                    lifecycleScope.launch(IO) {
                        appDb.sourceFilterRuleDao.deleteAll()
                        SearchBookFilter.reload()
                    }
                }
                noButton()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_enable_selection -> viewModel.enableSelection(adapter.selection)
            R.id.menu_disable_selection -> viewModel.disableSelection(adapter.selection)
            R.id.menu_top_sel -> viewModel.topSelect(adapter.selection)
            R.id.menu_bottom_sel -> viewModel.bottomSelect(adapter.selection)
            R.id.menu_export_selection -> exportResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    "exportSourceFilterRule.json",
                    GSON.toJson(adapter.selection).toByteArray(),
                    "application/json"
                )
            }
        }
        return false
    }

    private fun initRecyclerView() {
        binding.tvEmptyMsg.setText(R.string.source_filter_rule_empty)
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        val itemTouchCallback = ItemTouchCallback(adapter)
        itemTouchCallback.isCanDrag = true
        val dragSelectTouchHelper =
            DragSelectTouchHelper(adapter.dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        dragSelectTouchHelper.activeSlideSelect()
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.search)
        searchView.setOnQueryTextListener(this)
    }

    private fun initSelectActionView() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.replace_rule_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

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
                adapter.setItems(rules, adapter.diffItemCallBack)
                dataInit = true
                binding.tvEmptyMsg.visibility =
                    if (rules.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
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
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()
                text?.let {
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

    override fun onQueryTextChange(newText: String?): Boolean {
        observe(newText)
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean = false

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) adapter.selectAll() else adapter.revertSelection()
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            yesButton { viewModel.delSelection(adapter.selection) }
            noButton()
        }
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selection.size, adapter.itemCount)
    }

    override fun update(vararg rule: SourceFilterRule) {
        viewModel.update(*rule)
    }

    override fun delete(rule: SourceFilterRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rule.name)
            noButton()
            yesButton { viewModel.delete(rule) }
        }
    }

    override fun edit(rule: SourceFilterRule) {
        showDialogFragment(SourceFilterEditDialog(existing = rule))
    }

    override fun toTop(rule: SourceFilterRule) {
        viewModel.toTop(rule)
    }

    override fun toBottom(rule: SourceFilterRule) {
        viewModel.toBottom(rule)
    }

    override fun upOrder() {
        viewModel.upOrder()
    }

    override fun onSourceFilterRuleSave(rule: SourceFilterRule, isNew: Boolean) {
        lifecycleScope.launch(IO) {
            SearchBookFilter.save(rule, isNew)
        }
    }
}
