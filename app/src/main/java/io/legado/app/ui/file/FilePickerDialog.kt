package io.legado.app.ui.file

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isGone
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogFileChooserBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.ui.book.import.local.ImportBookAdapter
import io.legado.app.ui.file.HandleFileContract.Companion.FILE
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.io.File

class FilePickerDialog : BaseDialogFragment(R.layout.dialog_file_chooser),
    Toolbar.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    ImportBookAdapter.CallBack {

    companion object {
        const val tag = "FileChooserDialog"

        fun show(
            manager: FragmentManager,
            mode: Int = FILE,
            title: String? = null,
            initPath: String? = null,
            isShowHideDir: Boolean = false,
            allowExtensions: Array<String>? = null,
        ) {
            FilePickerDialog().apply {
                val bundle = Bundle()
                bundle.putInt("mode", mode)
                bundle.putString("title", title)
                bundle.putBoolean("isShowHideDir", isShowHideDir)
                bundle.putString("initPath", initPath)
                bundle.putStringArray("allowExtensions", allowExtensions)
                arguments = bundle
            }.show(manager, tag)
        }
    }

    private val binding by viewBinding(DialogFileChooserBinding::bind)
    private val viewModel by viewModels<FilePickerViewModel>()
    private val adapter by lazy {
        ImportBookAdapter(
            requireContext(),
            this
        ).apply { isFileManageMode = true }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initMenu()
        initView()
        viewModel.filesLiveData.observe(viewLifecycleOwner) {
            binding.refreshProgressBar.isAutoLoading = false
            binding.tvEmptyMsg.isGone = it.isNotEmpty()
            val items = it.map { file ->
                val isUpDir = file == viewModel.lastDir && file != viewModel.rootDoc
                ImportBook(FileDoc.fromFile(file), isUpDir = isUpDir, isFileManageMode = true)
            }
            adapter.setItems(items)
            upPath()
        }
        viewModel.initData(arguments)
        binding.toolBar.title = arguments?.getString("title") ?: let {
            if (viewModel.isSelectDir) {
                getString(R.string.folder_chooser)
            } else {
                getString(R.string.file_chooser)
            }
        }
    }

    private fun initView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        binding.selectActionBar.setMainActionText(R.string.confirm)
        binding.selectActionBar.setCallBack(this)
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.file_chooser)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
    }

    override fun onMenuItemClick(item: android.view.MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_create -> alert(R.string.create_folder) {
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = "文件夹名"
                }
                customView { alertBinding.root }
                okButton {
                    val text = alertBinding.editView.text?.toString()
                    if (text.isNullOrBlank()) {
                        toastOnUi("文件夹名不能为空")
                    } else {
                        viewModel.createFolder(text.trim())
                    }
                }
                cancelButton()
            }
        }
        return true
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

    private fun setResultData(path: String) {
        val data = Intent().setData(Uri.fromFile(File(path)))
        (parentFragment as? CallBack)?.onResult(data)
        (activity as? CallBack)?.onResult(data)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        activity?.finish()
    }

    override fun goBack() {
        if (viewModel.subDocs.isNotEmpty()) {
            viewModel.subDocs.removeAt(viewModel.subDocs.lastIndex)
            viewModel.upFiles(viewModel.lastDir)
        }
    }

    override fun nextDoc(fileDoc: FileDoc) {
        fileDoc.asFile()?.let {
            viewModel.subDocs.add(it)
            viewModel.upFiles(it)
        }
    }

    override fun openFile(fileDoc: FileDoc) {
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selected.size, adapter.checkableCount)
    }

    override fun startRead(fileDoc: FileDoc) {
        if (viewModel.isSelectFile) {
            viewModel.allowExtensions.let {
                if (it.isNullOrEmpty() || it.contains(FileUtils.getExtension(fileDoc.name))) {
                    setResultData(fileDoc.toString())
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    override fun selectAll(selectAll: Boolean) {
        adapter.selectAll(selectAll)
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        if (viewModel.isSelectDir) {
            viewModel.lastDir?.let {
                setResultData(it.path)
                dismissAllowingStateLoss()
            }
        } else {
            val file = adapter.selected.firstOrNull()?.file
            if (file == null) {
                toastOnUi("请选择文件")
            } else {
                setResultData(file.toString())
                dismissAllowingStateLoss()
            }
        }
    }

    interface CallBack {
        fun onResult(data: Intent)
    }
}
