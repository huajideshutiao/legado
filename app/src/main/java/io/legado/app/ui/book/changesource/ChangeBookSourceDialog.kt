package io.legado.app.ui.book.changesource

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.primaryStr
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.observeEvent
import io.legado.app.utils.startActivity
import io.legado.app.utils.throttleLatest
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 换源界面
 */
class ChangeBookSourceDialog() : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    constructor(name: String, author: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
            putString("author", author)
        }
    }

    private val callBack: CallBack? get() = activity as? CallBack
    private val viewModel: ChangeBookSourceViewModel by viewModels()
    private val waitDialog by lazy { WaitDialog.from(requireActivity()) }
    private val editSourceResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            val origin = it.data?.getStringExtra("origin") ?: return@registerForActivityResult
            viewModel.startSearch(origin)
        }

    private var searching by mutableStateOf(false)
    private var curBookUrl by mutableStateOf<String?>(null)
    private var durText by mutableStateOf("")
    private var searchGroup by mutableStateOf(AppConfig.searchGroup)

    private val searchFinishCallback: (isEmpty: Boolean) -> Unit = {
        if (it) {
            val group = AppConfig.searchGroup
            if (group.isNotEmpty()) {
                lifecycleScope.launch {
                    context?.alert("搜索结果为空") {
                        setMessage("${group}分组搜索结果为空,是否切换到全部分组")
                        cancelButton()
                        okButton {
                            AppConfig.searchGroup = ""
                            searchGroup = ""
                            viewModel.startSearch()
                        }
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.initData(arguments, callBack?.oldBook, activity is ReadBookActivity)
        curBookUrl = callBack?.oldBook?.bookUrl
        durText = callBack?.oldBook?.originName ?: ""
        viewModel.searchFinishCallback = searchFinishCallback
        viewModel.searchStateData.observe(viewLifecycleOwner) { searching = it }
        observeEvent<String>(EventBus.SOURCE_CHANGED) {
            curBookUrl = callBack?.oldBook?.bookUrl
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.searchFinishCallback = null
    }

    @Composable
    override fun Content() {
        var items by remember { mutableStateOf(emptyList<SearchBook>()) }
        var groups by remember { mutableStateOf(emptyList<String>()) }
        var searchMode by rememberSaveable { mutableStateOf(false) }
        var screenKey by rememberSaveable { mutableStateOf("") }
        var checkAuthor by remember { mutableStateOf(AppConfig.changeSourceCheckAuthor) }
        var loadInfo by remember { mutableStateOf(AppConfig.changeSourceLoadInfo) }
        var loadToc by remember { mutableStateOf(AppConfig.changeSourceLoadToc) }
        var loadWordCount by remember { mutableStateOf(AppConfig.changeSourceLoadWordCount) }
        // 分组二级菜单独立 Dialog 状态：避免嵌套 Popup 位置错乱
        var showGroupPicker by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current

        LaunchedEffect(Unit) {
            lifecycle.currentStateFlow.first { it.isAtLeast(STARTED) }
            viewModel.searchDataFlow.throttleLatest(1_000).collect { items = it }
        }
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(STARTED) {
                viewModel.changeSourceProgress
                    .drop(1)
                    .throttleLatest(500)
                    .collect { (count, name) ->
                        durText = getString(
                            R.string.change_source_progress,
                            items.size,
                            count,
                            viewModel.totalSourceCount,
                            name
                        )
                    }
            }
        }
        LaunchedEffect(Unit) {
            appDb.bookSourceDao.flowEnabledGroups().conflate().collect {
                groups = it
            }
        }
        // 对照 AdapterDataObserver：首条变化(插入/移动到 0)回滚到顶
        LaunchedEffect(items.firstOrNull()?.bookUrl) {
            if (items.isNotEmpty()) listState.scrollToItem(0)
        }

        Column(Modifier.fillMaxSize()) {
            ChangeSourceTitleBar(
                title = viewModel.name,
                subtitle = viewModel.author,
                searchMode = searchMode,
                screenKey = screenKey,
                searching = searching,
                onBack = { dismissAllowingStateLoss() },
                onSearchModeChange = { searchMode = it },
                onScreen = {
                    screenKey = it
                    viewModel.screen(it)
                },
                onStartStop = { viewModel.startOrStopSearch() },
            ) { dismiss ->
                TextMenuItem(stringResource(R.string.book_source_manage)) {
                    dismiss(); startActivity<BookSourceActivity>()
                }
                TextMenuItem(stringResource(R.string.refresh_list)) {
                    dismiss(); viewModel.startRefreshList()
                }
                CheckMenuItem(stringResource(R.string.checkAuthor), checkAuthor) {
                    dismiss()
                    checkAuthor = !checkAuthor
                    AppConfig.changeSourceCheckAuthor = checkAuthor
                    viewModel.refresh()
                }
                CheckMenuItem(stringResource(R.string.load_word_count), loadWordCount) {
                    dismiss()
                    loadWordCount = !loadWordCount
                    AppConfig.changeSourceLoadWordCount = loadWordCount
                    viewModel.onLoadWordCountChecked(loadWordCount)
                }
                CheckMenuItem(stringResource(R.string.load_info), loadInfo) {
                    dismiss()
                    loadInfo = !loadInfo
                    AppConfig.changeSourceLoadInfo = loadInfo
                }
                CheckMenuItem(stringResource(R.string.load_toc), loadToc) {
                    dismiss()
                    loadToc = !loadToc
                    AppConfig.changeSourceLoadToc = loadToc
                }
                GroupMenuItem(
                    title = if (searchGroup.isEmpty()) {
                        stringResource(R.string.group)
                    } else {
                        stringResource(R.string.group) + "($searchGroup)"
                    },
                    dismissParent = dismiss,
                    onShowGroupPicker = { showGroupPicker = true },
                )
                TextMenuItem(stringResource(R.string.close)) {
                    dismiss(); dismissAllowingStateLoss()
                }
            }
            ChangeSourceRefreshBar(searching)
            FastScrollLazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(items, key = { it.bookUrl }) { book ->
                    SearchBookItem(
                        book = book,
                        isCurSource = book.bookUrl == curBookUrl,
                        loadWordCount = loadWordCount,
                        getScore = { viewModel.getBookScore(book) },
                        setScore = { viewModel.setBookScore(book, it) },
                        onClick = { if (book.bookUrl != curBookUrl) changeTo(book) },
                        onTop = { viewModel.topSource(book) },
                        onBottom = { viewModel.bottomSource(book) },
                        onEdit = { editSource(book) },
                        onDisable = { viewModel.disableSource(book) },
                        onDelete = { deleteSourceConfirm(book) },
                    )
                }
            }
            ChangeSourceBottomBar(
                durText = durText,
                onDurClick = {
                    val index = items.indexOfFirst { it.bookUrl == curBookUrl }
                    if (index >= 0) scope.launch {
                        listState.scrollToItem(index, with(density) { -60.dp.roundToPx() })
                    }
                },
                onTop = { scope.launch { listState.scrollToItem(0) } },
                onBottom = {
                    scope.launch { if (items.isNotEmpty()) listState.scrollToItem(items.lastIndex) }
                },
            )
        }
        // 分组选择独立 Dialog：弹出时居中显示，避免原嵌套 Popup 错位
        if (showGroupPicker) {
            GroupPickerDialog(
                groups = groups,
                selectedGroup = searchGroup,
                onDismiss = { showGroupPicker = false },
                onSelect = { group ->
                    showGroupPicker = false
                    onGroupSelected(group)
                },
            )
        }
    }

    private fun onGroupSelected(group: String) {
        if (group == AppConfig.searchGroup) return
        AppConfig.searchGroup = group
        searchGroup = group
        lifecycleScope.launch(IO) {
            viewModel.stopSearch()
            if (viewModel.refresh()) {
                viewModel.startSearch()
            }
        }
    }

    private fun changeTo(searchBook: SearchBook) {
        val oldBookType = callBack?.oldBook?.type ?: 0
        if (searchBook.sameBookTypeLocal(oldBookType)) {
            changeSource(searchBook) {
                dismissAllowingStateLoss()
            }
        } else {
            alert(
                titleResource = R.string.book_type_different,
                messageResource = R.string.soure_change_source
            ) {
                okButton {
                    changeSource(searchBook) {
                        dismissAllowingStateLoss()
                    }
                }
                cancelButton()
            }
        }
    }

    private fun editSource(searchBook: SearchBook) {
        editSourceResult.launch {
            putExtra("sourceUrl", searchBook.origin)
        }
    }

    private fun deleteSourceConfirm(searchBook: SearchBook) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + searchBook.originName)
            noButton()
            yesButton {
                deleteSource(searchBook)
            }
        }
    }

    private fun deleteSource(searchBook: SearchBook) {
        viewModel.del(searchBook)
        if (curBookUrl == searchBook.bookUrl) {
            viewModel.autoChangeSource(callBack?.oldBook?.type) { book, toc, source ->
                callBack?.changeTo(source, book, toc)
            }
        }
    }

    private fun changeSource(searchBook: SearchBook, onSuccess: (() -> Unit)? = null) {
        waitDialog.setText(R.string.load_toc)
        waitDialog.show(requireActivity().supportFragmentManager)
        val book = viewModel.bookMap[searchBook.primaryStr()] ?: searchBook.toBook()
        val coroutine = viewModel.getToc(book, { toc, source ->
            waitDialog.dismissSafe()
            callBack?.changeTo(source, book, toc)
            onSuccess?.invoke()
        }, {
            waitDialog.dismissSafe()
            AppLog.put("换源获取目录出错\n$it", it, true)
        })
        waitDialog.onCancelListener = {
            coroutine.cancel()
        }
    }

    interface CallBack {
        val oldBook: Book?
        fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>)
    }

}
