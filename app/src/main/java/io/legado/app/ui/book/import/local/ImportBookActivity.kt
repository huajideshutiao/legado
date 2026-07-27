package io.legado.app.ui.book.import.local

import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.book.import.BaseImportBookActivity
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isUri
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导入本地书籍界面(纯 Compose, UI 下沉 shared ImportBookScreen)。
 *
 * 实现 shared 端 [ImportBookUiActions] 接口供 [ImportBookScreen] 回调,
 * 已有同名方法直接 `override` (`onItemClick` / `onItemLongClick`);
 * 其余 `onXxx` 桥接方法委托到 Activity 内部同名私有/公有方法。
 *
 * 状态字段 (items/selected/refreshTick/path/loading/emptyMsgVisible/searchKey/
 * checkableCount/sortState) 由 Activity 托管, [Content] 内打包为 [ImportBookUiState]
 * 传入 shared 端 [ImportBookScreen]。
 */
class ImportBookActivity : BaseImportBookActivity(), ImportBookUiActions<ImportBook> {

    val viewModel by viewModels<ImportBookViewModel>()
    private var scanDocJob: Job? = null

    var items by mutableStateOf<List<ImportBook>>(emptyList())
        private set
    val selected = mutableStateOf<Set<ImportBook>>(emptySet())
    var emptyMsgVisible by mutableStateOf(false)
        private set
    var sortState by mutableStateOf(0)
        private set

    /** 上架标记原地变更后 +1 强制列表重组(对照 notifyDataSetChanged) */
    var refreshTick by mutableStateOf(0)
        private set

    private val selectFolder = registerHandleFile { result ->
        result.uri?.let { uri ->
            AppConfig.importBookPath = uri.toString()
            initRootDoc(true)
        }
    }

    val checkableCount: Int get() = items.count { isCheckable(it) }

    fun isCheckable(item: ImportBook): Boolean =
        !item.isUpDir && !item.isDir && !item.isOnBookShelf

    @Composable
    override fun Content() {
        val state = ImportBookUiState(
            items = items,
            selected = selected.value,
            refreshTick = refreshTick,
            path = path,
            loading = loading,
            emptyMsgVisible = emptyMsgVisible,
            searchKey = searchKey,
            checkableCount = checkableCount,
            sortState = sortState,
        )
        ImportBookScreen(state, this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        sortState = viewModel.sort
        setupBackPress { goBackDir() }
        lifecycleScope.launch {
            if (setBookStorage() && AppConfig.importBookPath.isNullOrBlank()) {
                AppConfig.importBookPath = AppConfig.defaultBookTreeUri
            }
            initData()
        }
    }

    fun pickFolder() = selectFolder.launch()

    // ---- ImportBookUiActions 桥接实现 (委托到 Activity 内部同名方法) ----

    override fun onBack() = finish()

    override fun onUpSearchKey(key: String) = upSearchKey(key)

    override fun onPickFolder() = pickFolder()

    override fun onUpSort(sort: Int) = upSort(sort)

    override fun onScanFolder() = scanFolder()

    override fun onAlertImportFileName() = alertImportFileName()

    override fun onSelectAll(selectAll: Boolean) = selectAll(selectAll)

    override fun onRevertSelection() = revertSelection()

    override fun onAddSelectionToBookshelf() = addSelectionToBookshelf()

    fun toggleSelect(item: ImportBook) {
        selected.value =
            if (item in selected.value) selected.value - item else selected.value + item
    }

    fun selectAll(selectAll: Boolean) {
        selected.value = if (selectAll) items.filter { isCheckable(it) }.toSet() else emptySet()
    }

    fun revertSelection() {
        selected.value = items.filter { isCheckable(it) }.toSet() - selected.value
    }

    fun addSelectionToBookshelf() {
        val books = HashSet(selected.value)
        viewModel.addToBookshelf(books) {
            books.forEach { it.isOnBookShelf = true }
            selected.value = emptySet()
            refreshTick++
        }
    }

    override fun onItemClick(item: ImportBook) {
        when {
            item.isUpDir -> goBackDir()
            item.isDir -> nextDoc(item.file)
            !item.isOnBookShelf -> toggleSelect(item)
            else -> startRead(item.file)
        }
    }

    override fun onItemLongClick(item: ImportBook) {
        if (!item.isUpDir && !item.isDir && !item.isOnBookShelf) {
            startRead(item.file)
        }
    }

    private fun initData() {
        viewModel.dataFlowStart = {
            initRootDoc()
        }
        lifecycleScope.launch {
            viewModel.dataFlow.conflate().collect { docs ->
                items = if (viewModel.subDocs.isNotEmpty()) {
                    val upDirBook = ImportBook(viewModel.subDocs.last(), isUpDir = true)
                    listOf(upDirBook) + docs
                } else {
                    docs
                }
            }
        }
    }

    private fun initRootDoc(changedFolder: Boolean = false) {
        if (viewModel.rootDoc != null && !changedFolder) {
            upPath()
        } else {
            val lastPath = AppConfig.importBookPath
            if (lastPath.isNullOrBlank()) {
                emptyMsgVisible = true
                selectFolder.launch()
            } else {
                val rootUri = if (lastPath.isUri()) {
                    lastPath.toUri()
                } else {
                    Uri.fromFile(File(lastPath))
                }
                when {
                    rootUri.isContentScheme() -> initRootPath(rootUri)
                    else -> initRootPath(rootUri.path!!)
                }
            }
        }
    }

    private fun initRootPath(rootUri: Uri) {
        kotlin.runCatching {
            val doc = DocumentFile.fromTreeUri(this, rootUri)
            if (doc == null || doc.name.isNullOrEmpty() || !doc.isDirectory) {
                emptyMsgVisible = true
                selectFolder.launch()
            } else {
                viewModel.subDocs.clear()
                viewModel.rootDoc = FileDoc.fromDocumentFile(doc)
                upPath()
            }
        }.onFailure {
            emptyMsgVisible = true
            selectFolder.launch()
        }
    }

    private fun initRootPath(path: String) {
        emptyMsgVisible = true
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                kotlin.runCatching {
                    val file = File(path)
                    if (!file.isDirectory) {
                        emptyMsgVisible = true
                        selectFolder.launch()
                    } else {
                        viewModel.subDocs.clear()
                        viewModel.rootDoc = FileDoc.fromFile(file)
                        upPath()
                    }
                }.onFailure {
                    emptyMsgVisible = true
                    selectFolder.launch()
                }
            }
            .request()
    }

    fun upSort(sort: Int) {
        viewModel.sort = sort
        sortState = sort
        AppConfig.localBookImportSort = sort
        if (scanDocJob?.isActive != true) {
            viewModel.dataCallback?.upAdapter()
        }
    }

    @Synchronized
    private fun upPath() {
        viewModel.rootDoc?.let {
            scanDocJob?.cancel()
            upDocs(it)
        }
    }

    private fun upDocs(rootDoc: FileDoc) {
        emptyMsgVisible = false
        var path = rootDoc.name + File.separator
        var lastDoc = rootDoc
        for (doc in viewModel.subDocs) {
            lastDoc = doc
            path = path + doc.name + File.separator
        }
        // 显示面包屑路径
        showBreadcrumb(path)
        selected.value = emptySet()
        viewModel.loadDoc(lastDoc)
    }

    /**
     * 扫描当前文件夹及所有子文件夹
     */
    fun scanFolder() {
        viewModel.rootDoc?.let { doc ->
            items = emptyList()
            val lastDoc = viewModel.subDocs.lastOrNull() ?: doc
            loading = true
            scanDocJob?.cancel()
            scanDocJob = lifecycleScope.launch(IO) {
                viewModel.scanDoc(lastDoc)
                withContext(Main) {
                    loading = false
                }
            }
        }
    }

    fun alertImportFileName() {
        alert(R.string.import_file_name) {
            setMessage("""使用js处理文件名变量src，将书名作者分别赋值到变量name author""")
            val getText = editTextView(hint = "js", text = AppConfig.bookImportFileName ?: "")
            okButton {
                AppConfig.bookImportFileName = getText()
            }
            cancelButton()
        }
    }

    @Synchronized
    private fun nextDoc(fileDoc: FileDoc) {
        viewModel.subDocs.add(fileDoc)
        upPath()
    }

    @Synchronized
    private fun goBackDir(): Boolean {
        return if (viewModel.subDocs.isNotEmpty()) {
            viewModel.subDocs.removeAt(viewModel.subDocs.lastIndex)
            upPath()
            true
        } else {
            false
        }
    }

    override fun onSearchTextChange(newText: String?) {
        viewModel.updateCallBackFlow(newText)
    }

    private fun startRead(fileDoc: FileDoc) {
        if (!ArchiveUtils.isArchive(fileDoc.name)) {
            runBlocking { appDb.bookDao.getBookByFileName(fileDoc.name) }?.let {
                val filePath = fileDoc.toString()
                if (it.bookUrl != filePath) {
                    it.bookUrl = filePath
                    runBlocking { appDb.bookDao.insert(it) }
                }
                startReadBook(it)
            }
        } else {
            onArchiveFileClick(fileDoc)
        }
    }

}
