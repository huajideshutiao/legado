package io.legado.app.ui.book.changesource

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.observeEvent
import io.legado.app.utils.startActivity
import io.legado.app.utils.throttleLatest
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


class ChangeChapterSourceDialog() : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    constructor(name: String, author: String, chapterIndex: Int, chapterTitle: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
            putString("author", author)
            putInt("chapterIndex", chapterIndex)
            putString("chapterTitle", chapterTitle)
        }
    }

    private val callBack: CallBack? get() = activity as? CallBack
    private val viewModel: ChangeChapterSourceViewModel by viewModels()
    private val editSourceResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            viewModel.startSearch()
        }

    private var searching by mutableStateOf(false)
    private var curBookUrl by mutableStateOf<String?>(null)
    private var searchGroup by mutableStateOf(AppConfig.searchGroup)
    private var searchBook: SearchBook? = null

    // toc 预览覆盖层状态
    private var tocVisible by mutableStateOf(false)
    private var tocLoading by mutableStateOf(false)
    private var tocList by mutableStateOf<List<BookChapter>?>(null)
    private var durChapterIndex by mutableIntStateOf(0)

    private val contentSuccess: (content: String) -> Unit = {
        tocLoading = false
        callBack?.replaceContent(it)
        dismissAllowingStateLoss()
    }
    private val searchFinishCallback: (isEmpty: Boolean) -> Unit = {
        if (it) {
            val group = AppConfig.searchGroup
            if (group.isNotEmpty()) {
                lifecycleScope.launch {
                    context?.alert("搜索结果为空") {
                        setMessage("${group}分组搜索结果为空,是否切换到全部分组")
                        noButton()
                        yesButton {
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
        viewModel.searchFinishCallback = searchFinishCallback
        viewModel.searchStateData.observe(viewLifecycleOwner) { searching = it }
        observeEvent<String>(EventBus.SOURCE_CHANGED) {
            curBookUrl = callBack?.oldBook?.bookUrl
        }
        // 返回键先收起 toc 再关闭对话框
        (dialog as? ComponentDialog)?.onBackPressedDispatcher?.addCallback(this) {
            if (tocVisible) {
                tocVisible = false
                return@addCallback
            }
            dismissAllowingStateLoss()
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
                title = viewModel.chapterTitle,
                subtitle = null,
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
                    title = stringResource(R.string.group),
                    dismissParent = dismiss,
                    onShowGroupPicker = { showGroupPicker = true },
                )
            }
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize()) {
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
                                onClick = { openToc(book) },
                                onTop = { viewModel.topSource(book) },
                                onBottom = { viewModel.bottomSource(book) },
                                onEdit = { editSource(book) },
                                onDisable = { viewModel.disableSource(book) },
                                onDelete = { deleteSource(book) },
                            )
                        }
                    }
                    ChangeSourceBottomBar(
                        durText = callBack?.oldBook?.originName ?: "",
                        onDurClick = {
                            val index = items.indexOfFirst { it.bookUrl == curBookUrl }
                            if (index >= 0) scope.launch {
                                listState.scrollToItem(index, with(density) { -60.dp.roundToPx() })
                            }
                        },
                        onTop = { scope.launch { listState.scrollToItem(0) } },
                        onBottom = {
                            scope.launch {
                                if (items.isNotEmpty()) listState.scrollToItem(items.lastIndex)
                            }
                        },
                    )
                }
                if (tocVisible) {
                    ChapterTocPanel(
                        toc = tocList,
                        durChapterIndex = durChapterIndex,
                        loading = tocLoading,
                        onHide = { tocVisible = false },
                        onClickChapter = ::clickChapter,
                    )
                }
            }
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

    private fun openToc(searchBook: SearchBook) {
        this.searchBook = searchBook
        tocList = null
        tocVisible = true
        tocLoading = true
        val book = searchBook.toBook()
        viewModel.getToc(book, { toc: List<BookChapter>, _: BookSource ->
            durChapterIndex =
                BookHelp.getDurChapter(viewModel.chapterIndex, viewModel.chapterTitle, toc)
            tocLoading = false
            tocList = toc
        }, {
            tocVisible = false
            AppLog.put("单章换源获取目录出错\n$it", it, true)
        })
    }

    private fun clickChapter(bookChapter: BookChapter, nextChapterUrl: String?) {
        searchBook?.let {
            tocLoading = true
            viewModel.getContent(it.toBook(), bookChapter, nextChapterUrl, contentSuccess) { msg ->
                tocLoading = false
                tocVisible = false
                toastOnUi(msg)
            }
        }
    }

    private fun editSource(searchBook: SearchBook) {
        editSourceResult.launch {
            putExtra("sourceUrl", searchBook.origin)
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

    interface CallBack {
        val oldBook: Book?
        fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>)
        fun replaceContent(content: String)
    }

}
