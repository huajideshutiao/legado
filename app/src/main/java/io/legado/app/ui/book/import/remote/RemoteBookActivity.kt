package io.legado.app.ui.book.import.remote

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.remote.RemoteBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.import.BaseImportBookActivity
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.find
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 展示远程书籍(纯 Compose, UI 下沉 shared RemoteBookScreen)。
 *
 * 实现 shared 端 [RemoteBookUiActions] 接口供 [RemoteBookScreen] 回调,
 * 已有同名方法直接 `override` (`onItemClick` / `onItemLongClick`);
 * 其余 `onXxx` 桥接方法委托到 Activity 内部同名公有方法。
 *
 * 状态字段 (items/selected/refreshTick/path/loading/emptyMsgVisible/searchKey/
 * checkableCount/sortKeyState) 由 Activity 托管, [Content] 内打包为 [RemoteBookUiState]
 * 传入 shared 端 [RemoteBookScreen]。
 */
class RemoteBookActivity : BaseImportBookActivity(),
    ServersDialog.Callback, RemoteBookUiActions<RemoteBook> {

    val viewModel by viewModels<RemoteBookViewModel>()

    var items by mutableStateOf<List<RemoteBook>>(emptyList())
        private set
    val selected = mutableStateOf<Set<RemoteBook>>(emptySet())
    var emptyMsgVisible by mutableStateOf(false)
        private set
    var sortKeyState by mutableStateOf(RemoteBookSort.Default)
        private set

    /** 上架标记原地变更后 +1 强制列表重组(对照 notifyDataSetChanged) */
    var refreshTick by mutableStateOf(0)
        private set

    val checkableCount: Int get() = items.count { isCheckable(it) }

    fun isCheckable(item: RemoteBook): Boolean =
        !item.isUpDir && !item.isDir && !item.isOnBookShelf

    @Composable
    override fun Content() {
        val state = RemoteBookUiState(
            items = items,
            selected = selected.value,
            refreshTick = refreshTick,
            path = path,
            loading = loading,
            emptyMsgVisible = emptyMsgVisible,
            searchKey = searchKey,
            checkableCount = checkableCount,
            sortKeyState = sortKeyState,
        )
        RemoteBookScreen(state, this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        sortKeyState = viewModel.sortKey
        setupBackPress { goBackDir() }
        lifecycleScope.launch {
            if (!setBookStorage()) {
                finish()
                return@launch
            }
            if (!LocalConfig.webDavBookHelpVersionIsLast) {
                showHelp("webDavBookHelp")
            }
            launch {
                viewModel.dataFlow.conflate().collect { sortedRemoteBooks ->
                    loading = false
                    emptyMsgVisible = sortedRemoteBooks.isEmpty()
                    items = if (viewModel.dirList.isNotEmpty()) {
                        val upDirBook = viewModel.dirList.last().copy(isUpDir = true)
                        listOf(upDirBook) + sortedRemoteBooks
                    } else {
                        sortedRemoteBooks
                    }
                }
            }
            viewModel.initData {
                upPath()
            }
        }
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        viewModel.permissionDenialLiveData.observe(this) {
            localBookTreeSelect.launch {
                title = getString(R.string.select_book_folder)
            }
        }
    }

    fun sortCheck(sortKey: RemoteBookSort) {
        if (viewModel.sortKey == sortKey) {
            viewModel.sortAscending = !viewModel.sortAscending
        } else {
            viewModel.sortAscending = true
            viewModel.sortKey = sortKey
        }
        sortKeyState = viewModel.sortKey
        upPath()
    }

    // ---- RemoteBookUiActions 桥接实现 (委托到 Activity 内部同名公有方法) ----

    override fun onBack() = finish()

    override fun onUpSearchKey(key: String) = upSearchKey(key)

    override fun onUpPath() = upPath()

    override fun onSortCheck(sortKey: RemoteBookSort) = sortCheck(sortKey)

    override fun onShowServersDialog() = showServersDialog()

    override fun onShowWebDavHelp() = showWebDavHelp()

    override fun onShowLogDialog() = showLogDialog()

    override fun onSelectAll(selectAll: Boolean) = selectAll(selectAll)

    override fun onRevertSelection() = revertSelection()

    override fun onAddSelectionToBookshelf() = addSelectionToBookshelf()

    fun toggleSelect(item: RemoteBook) {
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
        loading = true
        viewModel.addToBookshelf(HashSet(selected.value)) {
            selected.value = emptySet()
            refreshTick++
            loading = false
        }
    }

    override fun onItemClick(item: RemoteBook) {
        when {
            item.isUpDir -> goBackDir()
            item.isDir -> {
                viewModel.dirList.add(item)
                upPath()
            }

            !item.isOnBookShelf -> toggleSelect(item)
            else -> startRead(item)
        }
    }

    override fun onItemLongClick(item: RemoteBook) {
        if (!item.isUpDir && item.isOnBookShelf) {
            addToBookShelfAgain(item)
        }
    }

    private fun goBackDir(): Boolean {
        if (viewModel.dirList.isEmpty()) {
            return false
        }
        viewModel.dirList.removeLastOrNull()
        upPath()
        return true
    }

    fun upPath() {
        var path = if (viewModel.isDefaultWebdav) {
            "books" + File.separator
        } else {
            File.separator
        }
        viewModel.dirList.forEach {
            path = path + it.filename + File.separator
        }
        // 显示面包屑路径
        showBreadcrumb(path)
        viewModel.dataCallback?.clear()
        selected.value = emptySet()
        viewModel.loadRemoteBookList(
            viewModel.dirList.lastOrNull()?.path
        ) {
            loading = it
        }
    }

    fun showServersDialog() = showDialogFragment<ServersDialog>()

    fun showLogDialog() = showDialogFragment<AppLogDialog>()

    fun showWebDavHelp() = showHelp("webDavBookHelp")

    override fun onDialogDismiss(tag: String) {
        viewModel.initData {
            upPath()
        }
    }

    override fun onSearchTextChange(newText: String?) {
        viewModel.updateCallBackFlow(newText)
    }

    private fun showRemoteBookDownloadAlert(
        remoteBook: RemoteBook,
        onDownloadFinish: (() -> Unit)? = null
    ) {
        alert(
            R.string.draw,
            R.string.archive_not_found
        ) {
            okButton {
                viewModel.addToBookshelf(hashSetOf(remoteBook)) {
                    onDownloadFinish?.invoke()
                }
            }
            noButton()
        }
    }

    private fun startRead(remoteBook: RemoteBook) {
        val downloadFileName = remoteBook.filename
        if (!ArchiveUtils.isArchive(downloadFileName)) {
            runBlocking { appDb.bookDao.getBookByFileName(downloadFileName) }?.let {
                startReadBook(it)
            }
        } else {
            AppConfig.defaultBookTreeUri ?: return
            val downloadArchiveFileDoc =
                FileDoc.fromUri(AppConfig.defaultBookTreeUri!!.toUri(), true)
                    .find(downloadFileName)
            if (downloadArchiveFileDoc == null) {
                showRemoteBookDownloadAlert(remoteBook) {
                    startRead(remoteBook)
                }
            } else {
                onArchiveFileClick(downloadArchiveFileDoc)
            }
        }
    }

    private fun addToBookShelfAgain(remoteBook: RemoteBook) {
        alert(getString(R.string.sure), "是否重新加入书架？") {
            yesButton {
                loading = true
                viewModel.addToBookshelf(hashSetOf(remoteBook)) {
                    loading = false
                }
            }
            noButton()
        }
    }

}
