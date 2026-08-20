package io.legado.app.ui.route

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.primaryStr
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.book.changesource.ChangeBookSourcePlatformProviders
import io.legado.app.ui.book.changesource.ChangeBookSourceViewModelShared
import io.legado.app.ui.book.changesource.ChangeSourceItemActions
import io.legado.app.ui.book.changesource.ChangeSourceMenuActions
import io.legado.app.ui.book.changesource.ChangeSourceScreen
import io.legado.app.ui.book.changesource.ChangeSourceScreenModel
import io.legado.app.ui.book.changesource.ChangeSourceUiActions
import io.legado.app.ui.book.changesource.ChangeSourceUiEvent
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppBottomSheetDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResult
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.eventObservable
import io.legado.app.utils.format
import io.legado.app.utils.throttleLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.book_type_different
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.change_source_progress
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.load_toc
import legado.shared.generated.resources.no
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.search_result_empty
import legado.shared.generated.resources.soure_change_source
import legado.shared.generated.resources.sure_del
import legado.shared.generated.resources.yes
import org.jetbrains.compose.resources.stringResource

/**
 * 整书换源弹窗形态 (对照原版 ChangeBookSourceDialog: 全高底部弹窗)。
 * 由阅读页"换源"按钮 / 书籍详情页长按"来源"弹起; 切源成功经 [onSourceChanged] 回传
 * (宿主负责 changeTo 落地 + 关闭)。
 */
@Composable
fun ChangeSourceDialogHost(
    book: Book,
    onSourceChanged: (source: BookSource, newBook: Book, toc: List<BookChapter>) -> Unit,
    onEditSource: (origin: String) -> Unit,
    onBookSourceManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheetDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            Surface(
                shape = DesignTokens.dialogShape,
                color = AppTheme.colors.background,
                modifier = Modifier.fillMaxSize(),
            ) {
                ChangeSourceContent(
                    book = book,
                    onBack = onDismiss,
                    onSourceChanged = onSourceChanged,
                    onEditSource = { origin ->
                        onDismiss()
                        onEditSource(origin)
                    },
                    onBookSourceManage = {
                        onDismiss()
                        onBookSourceManage()
                    },
                    bookSourceEditFlow = null,
                )
            }
        }
    }
}

/**
 * 换源屏幕共享正文 (路由/弹窗两形态共用)。
 *
 * 对照 app 端 [io.legado.app.ui.book.changesource.ChangeBookSourceDialog] 完整下沉:
 * - 实例化 [ChangeBookSourceViewModelShared] + [ChangeSourceScreenModel] 并桥接各 Flow;
 * - 切源/删除/编辑动作经回调外抛, 由宿主决定 pop/push 或 dismiss。
 *
 * @param book 当前书籍
 * @param onBack 返回 (路由=pop, 弹窗=dismiss)
 * @param onSourceChanged 切源成功 (source, newBook, toc)
 * @param onEditSource 编辑书源 (origin)
 * @param onBookSourceManage 打开书源管理
 * @param bookSourceEditFlow 书源编辑返回事件流 (路由形态传入, 弹窗形态已 dismiss 无需刷新)
 */
@Composable
fun ChangeSourceContent(
    book: Book,
    onBack: () -> Unit,
    onSourceChanged: (source: BookSource, newBook: Book, toc: List<BookChapter>) -> Unit,
    onEditSource: (origin: String) -> Unit,
    onBookSourceManage: () -> Unit,
    bookSourceEditFlow: Flow<RouteResult>? = null,
) {
    val screenModel = remember(book.bookUrl) { ChangeSourceScreenModel() }
    val state by screenModel.state.collectAsState()

    val scope = rememberCoroutineScope()
    val platform = ChangeBookSourcePlatformProviders.get()
    val viewModel = remember(book.bookUrl) {
        ChangeBookSourceViewModelShared(scope = scope, platform = platform)
    }
    // 释放搜索线程池 (对照 app 端 ViewModel.onCleared)
    DisposableEffect(viewModel) {
        onDispose { viewModel.onCleared() }
    }

    // region 对话框状态 (对照 app 端 alert 调用 + waitDialog)
    var showWaitDialog by remember { mutableStateOf(false) }
    var tocCoroutine by remember { mutableStateOf<Coroutine<*>?>(null) }
    var showEmptyGroupAlert by remember { mutableStateOf(false) }
    var showBookTypeDifferentAlert by remember { mutableStateOf(false) }
    var pendingChangeBook by remember { mutableStateOf<SearchBook?>(null) }
    var showDeleteConfirmAlert by remember { mutableStateOf(false) }
    var pendingDeleteBook by remember { mutableStateOf<SearchBook?>(null) }
    // endregion

    // 预取格式化串 (rememberString 是 @Composable, 不能在 lambda 里调)
    val progressFormat = stringResource(Res.string.change_source_progress)

    // region LaunchedEffect: 初始化 + 桥接 Flow

    // 对照 Dialog.onViewCreated 第 102-109 行: initData + curBookUrl + durText + 4 开关 + searchGroup
    // 键用 bookUrl (Book.equals 已改全字段语义, 整对象键会在进度等字段变化时误重跑 initData)
    LaunchedEffect(book.bookUrl) {
        viewModel.initData(book.name, book.author, fromReadBookActivity = false, oldBook = book)
        screenModel.dispatch(ChangeSourceUiEvent.BookInitialized(book))
        screenModel.dispatch(ChangeSourceUiEvent.CurBookUrlChanged(book.bookUrl))
        screenModel.dispatch(ChangeSourceUiEvent.DurTextChanged(book.originName))
        screenModel.dispatch(ChangeSourceUiEvent.CheckAuthorChanged(platform.changeSourceCheckAuthor))
        screenModel.dispatch(ChangeSourceUiEvent.LoadInfoChanged(platform.changeSourceLoadInfo))
        screenModel.dispatch(ChangeSourceUiEvent.LoadTocChanged(platform.changeSourceLoadToc))
        screenModel.dispatch(ChangeSourceUiEvent.LoadWordCountChanged(platform.changeSourceLoadWordCount))
        screenModel.dispatch(ChangeSourceUiEvent.SearchGroupChanged(platform.searchGroup))
        // 桥接 searchDataFlow (对照 Dialog.Content 第 135-138 行)
        viewModel.searchDataFlow.throttleLatest(1_000).collect { sources ->
            screenModel.dispatch(ChangeSourceUiEvent.SourcesLoaded(sources))
        }
    }

    // 桥接 searchState (对照 Dialog.onViewCreated 第 106 行)
    LaunchedEffect(Unit) {
        viewModel.searchState.collect { searching ->
            screenModel.dispatch(ChangeSourceUiEvent.LoadingChanged(searching))
            if (searching) {
                // 搜索开始时 bookSources 已填充, 更新 totalSourceCount 供进度文案用
                screenModel.dispatch(
                    ChangeSourceUiEvent.TotalSourceCountChanged(viewModel.totalSourceCount)
                )
            }
        }
    }

    // 桥接 changeSourceProgress (对照 Dialog.Content 第 139-154 行)
    LaunchedEffect(Unit) {
        viewModel.changeSourceProgress.drop(1).throttleLatest(500).collect { (count, name) ->
            val text = progressFormat.format(
                screenModel.state.value.sources.size,
                count,
                viewModel.totalSourceCount,
                name
            )
            screenModel.dispatch(ChangeSourceUiEvent.DurTextChanged(text))
        }
    }

    // 桥接 flowEnabledGroups (对照 Dialog.Content 第 155-159 行)
    LaunchedEffect(Unit) {
        AppDbProviders.get().bookSourceDao.flowEnabledGroups().conflate().collect { groups ->
            screenModel.dispatch(ChangeSourceUiEvent.GroupsLoaded(groups))
        }
    }

    // 设置 searchFinishCallback (对照 Dialog.onViewCreated 第 105 行 + searchFinishCallback 第 81-98 行)
    LaunchedEffect(Unit) {
        viewModel.searchFinishCallback = { isEmpty ->
            if (isEmpty) {
                val group = platform.searchGroup
                if (group.isNotEmpty()) {
                    showEmptyGroupAlert = true
                }
            }
        }
    }

    // 桥接 EventBus.SOURCE_CHANGED (对照 Dialog.onViewCreated 第 107-109 行)
    LaunchedEffect(Unit) {
        eventObservable(EventBus.SOURCE_CHANGED).collect {
            screenModel.dispatch(ChangeSourceUiEvent.CurBookUrlChanged(book.bookUrl))
        }
    }

    // 清理 searchFinishCallback (对照 Dialog.onDestroy 第 112-115 行)
    DisposableEffect(Unit) {
        onDispose {
            viewModel.searchFinishCallback = null
        }
    }

    // 书源编辑返回: 刷新源列表 (弹窗形态传 null, 编辑前已 dismiss)
    if (bookSourceEditFlow != null) {
        LaunchedEffect(bookSourceEditFlow) {
            bookSourceEditFlow.collect { viewModel.startRefreshList() }
        }
    }
    // endregion

    // region 切源流程 (对照 Dialog.changeTo + changeSource 第 284-345 行)

    /**
     * 切源核心: 显示 WaitDialog -> getToc -> 成功 onSourceChanged / 失败 AppLog。
     * 对照 app 端 changeSource(searchBook, onSuccess)。
     */
    fun changeSource(searchBook: SearchBook) {
        showWaitDialog = true
        val targetBook = viewModel.bookMap[searchBook.primaryStr()] ?: searchBook.toBook()
        tocCoroutine = viewModel.getToc(
            book = targetBook,
            onSuccess = { toc, source ->
                showWaitDialog = false
                tocCoroutine = null
                onSourceChanged(source, targetBook, toc)
            },
            onError = { e ->
                showWaitDialog = false
                tocCoroutine = null
                AppLog.put("换源获取目录出错\n$e", e, true)
            }
        )
    }

    /**
     * 切源入口: 检查书类型, 相同直接切, 不同弹确认 alert。
     * 对照 app 端 changeTo(searchBook)。
     */
    fun changeTo(searchBook: SearchBook) {
        val oldBookType = screenModel.state.value.book?.type ?: 0
        if (searchBook.sameBookTypeLocal(oldBookType)) {
            changeSource(searchBook)
        } else {
            pendingChangeBook = searchBook
            showBookTypeDifferentAlert = true
        }
    }

    /**
     * 删除书源后, 若删除的是当前源则自动换源。
     * 对照 app 端 deleteSource(searchBook)。
     */
    fun deleteSource(searchBook: SearchBook) {
        viewModel.del(searchBook)
        val currentState = screenModel.state.value
        if (currentState.curBookUrl == searchBook.bookUrl) {
            val oldBookType = currentState.book?.type
            viewModel.autoChangeSource(oldBookType) { newBook, toc, source ->
                onSourceChanged(source, newBook, toc)
            }
        }
    }
    // endregion

    // region Actions 实现

    val actions = object : ChangeSourceUiActions {
        override fun onBack() {
            onBack()
        }

        override fun onStartStop() {
            viewModel.startOrStopSearch()
        }

        override fun onScreen(key: String) {
            viewModel.screen(key)
        }

        override fun onSearchModeChange(enabled: Boolean) {
            // searchMode 为 Screen 本地 rememberSaveable 状态, 此回调无额外动作
        }

        override fun onItemClick(book: SearchBook) {
            // 对照 app 端 SearchBookItem onClick: if (book.bookUrl != curBookUrl) changeTo(book)
            if (book.bookUrl != screenModel.state.value.curBookUrl) {
                changeTo(book)
            }
        }
    }

    val menuActions = object : ChangeSourceMenuActions {
        override fun onBookSourceManage() {
            onBookSourceManage()
        }

        override fun onRefreshList() {
            viewModel.startRefreshList()
        }

        override fun onCheckAuthorChange(value: Boolean) {
            platform.changeSourceCheckAuthor = value
            screenModel.dispatch(ChangeSourceUiEvent.CheckAuthorChanged(value))
            viewModel.refresh()
        }

        override fun onLoadWordCountChange(value: Boolean) {
            platform.changeSourceLoadWordCount = value
            screenModel.dispatch(ChangeSourceUiEvent.LoadWordCountChanged(value))
            viewModel.onLoadWordCountChecked(value)
        }

        override fun onLoadInfoChange(value: Boolean) {
            platform.changeSourceLoadInfo = value
            screenModel.dispatch(ChangeSourceUiEvent.LoadInfoChanged(value))
        }

        override fun onLoadTocChange(value: Boolean) {
            platform.changeSourceLoadToc = value
            screenModel.dispatch(ChangeSourceUiEvent.LoadTocChanged(value))
        }

        override fun onClose() {
            onBack()
        }
    }

    val itemActions = object : ChangeSourceItemActions {
        override fun getScore(book: SearchBook): Int = viewModel.getBookScore(book)

        override fun setScore(book: SearchBook, score: Int) {
            viewModel.setBookScore(book, score)
        }

        override fun onTop(book: SearchBook) {
            viewModel.topSource(book)
        }

        override fun onBottom(book: SearchBook) {
            viewModel.bottomSource(book)
        }

        override fun onEdit(book: SearchBook) {
            onEditSource(book.origin)
        }

        override fun onDisable(book: SearchBook) {
            viewModel.disableSource(book)
        }

        override fun onDelete(book: SearchBook) {
            // 对照 app 端 deleteSourceConfirm (第 311-319 行)
            pendingDeleteBook = book
            showDeleteConfirmAlert = true
        }
    }
    // endregion

    // region 渲染 Screen + 对话框

    ChangeSourceScreen(
        state = state,
        book = book,
        actions = actions,
        menuActions = menuActions,
        itemActions = itemActions,
        searchGroup = state.searchGroup,
        onSearchGroupChange = { },
        onGroupPickerSelect = { group ->
            // 对照 app 端 onGroupSelected (第 272-282 行)
            if (group != platform.searchGroup) {
                platform.searchGroup = group
                screenModel.dispatch(ChangeSourceUiEvent.SearchGroupChanged(group))
                scope.launch {
                    viewModel.stopSearch()
                    if (viewModel.refresh()) {
                        viewModel.startSearch()
                    }
                }
            }
        },
        groups = state.groups,
    )

    // WaitDialog: 切源加载目录 (对照 app 端 waitDialog + load_toc)
    WaitDialog(
        visible = showWaitDialog,
        message = stringResource(Res.string.load_toc),
        onDismissRequest = {
            // 对照 app 端 waitDialog.onCancelListener = { coroutine.cancel() }
            showWaitDialog = false
            tocCoroutine?.cancel()
            tocCoroutine = null
        },
    )

    // 空结果切换全部分组 alert (对照 app 端 searchFinishCallback 第 86-94 行)
    if (showEmptyGroupAlert) {
        AppAlertDialog(
            onDismissRequest = { showEmptyGroupAlert = false },
            title = stringResource(Res.string.search_result_empty),
            message = "${platform.searchGroup}分组搜索结果为空,是否切换到全部分组",
            cancelButton = AlertButton(stringResource(Res.string.cancel)) { },
            okButton = AlertButton(stringResource(Res.string.ok)) {
                platform.searchGroup = ""
                screenModel.dispatch(ChangeSourceUiEvent.SearchGroupChanged(""))
                viewModel.startSearch()
            },
        )
    }

    // 书类型不同确认换源 alert (对照 app 端 changeTo 第 291-301 行)
    if (showBookTypeDifferentAlert) {
        AppAlertDialog(
            onDismissRequest = {
                showBookTypeDifferentAlert = false
                pendingChangeBook = null
            },
            title = stringResource(Res.string.book_type_different),
            message = stringResource(Res.string.soure_change_source),
            cancelButton = AlertButton(stringResource(Res.string.cancel)) { },
            okButton = AlertButton(stringResource(Res.string.ok)) {
                pendingChangeBook?.let { changeSource(it) }
            },
        )
    }

    // 删除书源确认 alert (对照 app 端 deleteSourceConfirm 第 311-319 行)
    if (showDeleteConfirmAlert) {
        AppAlertDialog(
            onDismissRequest = {
                showDeleteConfirmAlert = false
                pendingDeleteBook = null
            },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del) + "\n" + (pendingDeleteBook?.originName
                ?: ""),
            cancelButton = AlertButton(stringResource(Res.string.no)) { },
            okButton = AlertButton(stringResource(Res.string.yes)) {
                pendingDeleteBook?.let { deleteSource(it) }
            },
        )
    }
    // endregion
}
