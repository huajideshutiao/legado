package io.legado.app.ui.file

import android.content.Intent
import android.net.Uri
import android.os.Environment
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.ComposeDialog
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.i18n.androidAppString
import io.legado.app.ui.book.import.ImportFileRow
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.component.SelectActionBar
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.file.HandleFileContract.Companion.FILE
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.FileDoc
import io.legado.app.utils.toastOnUi
import java.io.File

class FilePickerDialog private constructor(
    activity: AppCompatActivity,
    private val callback: CallBack,
    private val mode: Int,
    private val title: String?,
    initPath: String?,
) : ComposeDialog(activity) {

    companion object {
        fun show(
            activity: AppCompatActivity,
            mode: Int = FILE,
            title: String? = null,
            initPath: String? = null,
            isShowHideDir: Boolean = false,
            allowExtensions: Array<String>? = null,
            callback: CallBack,
        ): FilePickerDialog {
            return FilePickerDialog(activity, callback, mode, title, initPath).also { it.show() }
        }
    }

    private var rootDoc: File? = Environment.getExternalStorageDirectory()
    private val subDocs = mutableListOf<File>()
    private val lastDir: File? get() = subDocs.lastOrNull() ?: rootDoc
    private val isSelectDir get() = mode == HandleFileContract.DIR

    private var items by mutableStateOf<List<ImportBook>>(emptyList())
    private var selected by mutableStateOf<Set<ImportBook>>(emptySet())
    private var path by mutableStateOf("")
    private var emptyVisible by mutableStateOf(false)

    /** 建目录弹窗: 本弹窗关闭 (请求收口/宿主销毁经弹窗关闭链生效) 时一并关闭。 */
    private var createFolderDialog: ComposeDialog? = null

    init {
        initPath?.let { rootDoc = File(it) }
        setComposeContent { Content() }
        setOnDismissListener { createFolderDialog?.dismiss() }
        upFiles(rootDoc)
    }

    @Composable
    private fun Content() {
        val colors = AppTheme.colors
        Column {
            DialogTitleBar(
                title = title ?: if (isSelectDir) rememberString("folder_chooser") else rememberString("file_chooser"),
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
                    .padding(DesignTokens.spacingDefault),
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
                    // 目录/文件少时列表随内容收缩 (对照原版对话框的自适应高度)
                    wrapContentHeight = true,
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
                            .padding(DesignTokens.spacingLg),
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
        if (subDocs.isNotEmpty()) {
            subDocs.removeAt(subDocs.lastIndex)
            upFiles(lastDir)
        }
    }

    private fun nextDoc(fileDoc: FileDoc) {
        fileDoc.asFile()?.let {
            subDocs.add(it)
            upFiles(it)
        }
    }

    private fun onConfirm() {
        if (isSelectDir) {
            lastDir?.let {
                setResultData(it.path)
                dismiss()
            }
        } else {
            val file = selected.firstOrNull()?.file
            if (file == null) {
                context.toastOnUi("请选择文件")
            } else {
                setResultData(file.toString())
                dismiss()
            }
        }
    }

    private fun showCreateFolderAlert() {
        createFolderDialog = context.alert(androidAppString("create_folder")) {
            val getText = editTextView(hint = "文件夹名", autoFocus = true)
            okButton {
                val text = getText()
                if (text.isBlank()) {
                    context.toastOnUi("文件夹名不能为空")
                } else {
                    createFolder(text.trim())
                }
            }
            cancelButton()
        }
    }

    private fun upFiles(parentFile: File?) {
        Coroutine.async(scope = lifecycleScope) {
            if (parentFile == null) return@async emptyList()
            if (parentFile == rootDoc) {
                parentFile.listFiles()?.sortedWith(
                    compareBy({ it.isFile }, { it.name })
                )
            } else {
                val list = arrayListOf(parentFile)
                parentFile.listFiles()?.sortedWith(
                    compareBy({ it.isFile }, { it.name })
                )?.let {
                    list.addAll(it)
                }
                list
            }
        }.onSuccess { files ->
            emptyVisible = files.isNullOrEmpty()
            items = files.orEmpty().map { file ->
                val isUpDir = file == lastDir && file != rootDoc
                ImportBook(FileDoc.fromFile(file), isUpDir = isUpDir, isFileManageMode = true)
            }
            upPath()
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }
    }

    private fun createFolder(name: String) {
        Coroutine.async(scope = lifecycleScope) {
            val dir = lastDir ?: throw NoStackTraceException("父文件夹不存在")
            val folder = File(dir, name)
            if (!folder.canonicalPath.contains(dir.canonicalPath)) {
                throw NoStackTraceException("非法文件名")
            }
            folder.mkdir()
        }.onSuccess {
            upFiles(lastDir)
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }
    }

    private fun upPath() {
        rootDoc?.let { rootDoc ->
            var path = rootDoc.name + File.separator
            for (doc in subDocs) {
                path = path + doc.name + File.separator
            }
            this.path = path
        }
    }

    private fun setResultData(path: String) {
        val data = Intent().setData(Uri.fromFile(File(path)))
        callback.onResult(data)
    }

    interface CallBack {
        fun onResult(data: Intent)
    }
}
