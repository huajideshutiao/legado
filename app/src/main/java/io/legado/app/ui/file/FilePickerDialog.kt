package io.legado.app.ui.file

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import io.legado.app.help.i18n.androidAppString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.ui.book.import.ImportFileRow
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.component.SelectActionBar
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.HandleFileContract.Companion.FILE
import io.legado.app.utils.FileDoc
import io.legado.app.utils.toastOnUi
import java.io.File
import org.jetbrains.compose.resources.stringResource

class FilePickerDialog : BaseComposeDialogFragment() {

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

    private val viewModel by viewModels<FilePickerViewModel>()
    private var items by mutableStateOf<List<ImportBook>>(emptyList())
    private var selected by mutableStateOf<Set<ImportBook>>(emptySet())
    private var path by mutableStateOf("")
    private var emptyVisible by mutableStateOf(false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.filesLiveData.observe(viewLifecycleOwner) { files ->
            emptyVisible = files.isEmpty()
            items = files.map { file ->
                val isUpDir = file == viewModel.lastDir && file != viewModel.rootDoc
                ImportBook(FileDoc.fromFile(file), isUpDir = isUpDir, isFileManageMode = true)
            }
            upPath()
        }
        viewModel.initData(arguments)
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        Column {
            DialogTitleBar(
                title = arguments?.getString("title") ?: if (viewModel.isSelectDir) rememberString("folder_chooser") else rememberString("file_chooser"),
                actions = {
                    IconButton(onClick = { showCreateFolderAlert() }) {
                        Icon(
                            painter = rememberPainter("ic_create_folder_outline"),
                            contentDescription = rememberString("create_folder"),
                            tint = colors.primaryText,
                        )
                    }
                },
            )
            Text(
                text = path,
                color = colors.secondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            )
            Spacer(Modifier.height(2.dp)) // 对照静态 RefreshProgressBar 占位
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                FastScrollLazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(items, key = { it.file.toString() }) { item ->
                        ImportFileRow(
                            name = item.name,
                            isDir = item.isDir,
                            isUpDir = item.isUpDir,
                            checkable = isCheckable(item),
                            checked = selected.contains(item),
                            onBookShelf = false,
                            tag = item.name.substringAfterLast("."),
                            size = item.size,
                            lastModified = item.lastModified,
                            onClick = { onItemClick(item) },
                        )
                    }
                }
                if (emptyVisible) {
                    Text(
                        text = rememberString("empty"),
                        color = colors.secondaryText,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                }
            }
            SelectActionBar(
                selectCount = selected.size,
                allCount = items.count { isCheckable(it) },
                onSelectAll = { all ->
                    selected = if (all) items.filter { isCheckable(it) }.toSet() else emptySet()
                },
                onRevertSelection = {
                    selected = items.filter { isCheckable(it) }.toSet() - selected
                },
                mainActionText = rememberString("confirm"),
                onMainAction = { onConfirm() },
            )
        }
    }

    private fun isCheckable(item: ImportBook): Boolean = !item.isUpDir && !item.isDir

    private fun onItemClick(item: ImportBook) {
        when {
            item.isUpDir -> goBack()
            item.isDir -> nextDoc(item.file)
            else -> selected = if (item in selected) selected - item else selected + item
        }
    }

    private fun goBack() {
        if (viewModel.subDocs.isNotEmpty()) {
            viewModel.subDocs.removeAt(viewModel.subDocs.lastIndex)
            viewModel.upFiles(viewModel.lastDir)
        }
    }

    private fun nextDoc(fileDoc: FileDoc) {
        fileDoc.asFile()?.let {
            viewModel.subDocs.add(it)
            viewModel.upFiles(it)
        }
    }

    private fun onConfirm() {
        if (viewModel.isSelectDir) {
            viewModel.lastDir?.let {
                setResultData(it.path)
                dismissAllowingStateLoss()
            }
        } else {
            val file = selected.firstOrNull()?.file
            if (file == null) {
                toastOnUi("请选择文件")
            } else {
                setResultData(file.toString())
                dismissAllowingStateLoss()
            }
        }
    }

    private fun showCreateFolderAlert() {
        alert(androidAppString("create_folder")) {
            val getText = editTextView(hint = "文件夹名", autoFocus = true)
            okButton {
                val text = getText()
                if (text.isBlank()) {
                    toastOnUi("文件夹名不能为空")
                } else {
                    viewModel.createFolder(text.trim())
                }
            }
            cancelButton()
        }
    }

    private fun upPath() {
        viewModel.rootDoc?.let { rootDoc ->
            var path = rootDoc.name + File.separator
            for (doc in viewModel.subDocs) {
                path = path + doc.name + File.separator
            }
            this.path = path
        }
    }

    private fun setResultData(path: String) {
        val data = Intent().setData(Uri.fromFile(File(path)))
        (parentFragment as? CallBack)?.onResult(data)
        (activity as? CallBack)?.onResult(data)
    }

    interface CallBack {
        fun onResult(data: Intent)
    }
}
