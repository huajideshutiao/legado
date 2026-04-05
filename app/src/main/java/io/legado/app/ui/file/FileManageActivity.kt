package io.legado.app.ui.file

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.databinding.ActivityImportBookBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.ui.book.import.local.ImportBookAdapter
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.utils.FileDoc
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.openFileUri
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.io.File

class FileManageActivity : VMBaseActivity<ActivityImportBookBinding, FileManageViewModel>(),
    SelectActionBar.CallBack,
    ImportBookAdapter.CallBack {

    override val binding by viewBinding(ActivityImportBookBinding::inflate)
    override val viewModel by viewModels<FileManageViewModel>()
    private val adapter by lazy { ImportBookAdapter(this, this).apply { isFileManageMode = true } }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        initSearchView()
        viewModel.upFiles(viewModel.rootDoc)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.file_manage, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_sort_name)?.isChecked = viewModel.sort == 0
        menu.findItem(R.id.menu_sort_size)?.isChecked = viewModel.sort == 1
        menu.findItem(R.id.menu_sort_time)?.isChecked = viewModel.sort == 2
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_sort_name -> upSort(0)
            R.id.menu_sort_size -> upSort(1)
            R.id.menu_sort_time -> upSort(2)
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        binding.layTop.setBackgroundColor(backgroundColor)
        binding.tvEmptyMsg.setText(R.string.empty)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.setCallBack(this)
        onBackPressedDispatcher.addCallback(this) {
            if (!goBackDir()) {
                finish()
            }
        }
    }

    private fun initSearchView() {
        val searchView =
            binding.titleBar.findViewById<androidx.appcompat.widget.SearchView>(R.id.search_view)
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.screen) + " • " + getString(R.string.file_manage)
        searchView.isSubmitButtonEnabled = true
        searchView.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return false
            }
        })
    }

    private fun upSort(sort: Int) {
        viewModel.sort = sort
        viewModel.upFiles(viewModel.lastDir)
    }

    private fun goBackDir(): Boolean {
        return if (viewModel.subDocs.isNotEmpty()) {
            viewModel.subDocs.removeAt(viewModel.subDocs.lastIndex)
            viewModel.upFiles(viewModel.lastDir)
            true
        } else {
            false
        }
    }

    override fun observeLiveBus() {
        viewModel.filesLiveData.observe(this) {
            val items = it.map { file ->
                val isUpDir = file == viewModel.lastDir && file != viewModel.rootDoc
                ImportBook(FileDoc.fromFile(file), isUpDir = isUpDir, isFileManageMode = true)
            }
            adapter.setItems(items)
            upPath()
        }
    }

    private fun upPath() {
        viewModel.rootDoc?.let { rootDoc ->
            var path = rootDoc.name + File.separator
            for (doc in viewModel.subDocs) {
                path = path + doc.name + File.separator
            }
            binding.tvPath.text = path
        }
    }

    override fun goBack() {
        goBackDir()
    }

    override fun nextDoc(fileDoc: FileDoc) {
        fileDoc.asFile()?.let {
            viewModel.subDocs.add(it)
            viewModel.upFiles(it)
        }
    }

    override fun openFile(fileDoc: FileDoc) {
        fileDoc.asFile()?.let {
            openFileUri(
                FileProvider.getUriForFile(
                    this,
                    AppConst.authority,
                    it
                )
            )
        }
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selected.size, adapter.checkableCount)
    }

    override fun startRead(fileDoc: FileDoc) {
        openFile(fileDoc)
    }

    override fun selectAll(selectAll: Boolean) {
        adapter.selectAll(selectAll)
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        viewModel.delFiles(adapter.selected.mapNotNull { it.file.asFile() }) {
            adapter.removeSelection()
        }
    }
}
