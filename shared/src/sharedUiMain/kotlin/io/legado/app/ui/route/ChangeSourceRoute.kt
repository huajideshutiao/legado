package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
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
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.eventObservable
import io.legado.app.utils.throttleLatest
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
import io.legado.app.utils.format

/**
 * 整书换源 shared 路由入口。
 *
 * 对照 app 端 [io.legado.app.ui.book.changesource.ChangeBookSourceDialog] 完整下沉:
 * - 实例化 [ChangeBookSourceViewModelShared] (组合委托模式, scope + platform 注入);
 * - 桥接 searchDataFlow / searchState / changeSourceProgress / flowEnabledGroups 到
 *   [ChangeSourceScreenModel] 状态;
 * - 桥接 searchFinishCallback + EventBus.SOURCE_CHANGED;
 * - 实现 [ChangeSourceUiActions] / [ChangeSourceMenuActions] / [ChangeSourceItemActions]
 *   将 navigator + viewModel + platform 串联;
 * - 渲染 [ChangeSourceScreen] + WaitDialog (切源加载目录) + 3 个确认 alert
 *   (空结果切换全部分组 / 书类型不同确认换源 / 删除书源确认)。
 *
 * 与 [ChangeChapterSourceRoute] (章节换源) 不同: 本路由 pop 时回传
 * [RouteResultPayload.ChangeSource] (source + book + toc), 由宿主处理整书换源。
 */
@Composable
fun ChangeSourceRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.ChangeSource
    val book = route.book.asBook()
    val screenModel = screenModelStore.getOrCreateTyped(entry) { ChangeSourceScreenModel() }
    val state by screenModel.state.collectAsState()

    val scope = rememberCoroutineScope()
    val platform = ChangeBookSourcePlatformProviders.get()
    val viewModel = remember(entry) {
        ChangeBookSourceViewModelShared(scope = scope, platform = platform)
    }

    // region 对话框状态 (对照 app 端 alert 调用 + waitDialog)
    var showGroupPicker by remember { mutableStateOf(false) }
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
    // 同时桥接 searchDataFlow (对照 Dialog.Content 第 135-138 行), 确保 initData 先于 collect 执行
    LaunchedEffect(route.book) {
        viewModel.initData(book.name, book.author, fromReadBookActivity = false, oldBook = book)
        screenModel.dispatch(ChangeSourceUiEvent.BookInitialized(book))
        screenModel.dispatch(ChangeSourceUiEvent.CurBookUrlChanged(book.bookUrl))
        screenModel.dispatch(ChangeSourceUiEvent.DurTextChanged(book.originName))
        // 初始化 4 个开关 + searchGroup (对照 app 端 remember { mutableStateOf(AppConfig.X) })
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

    // 书源编辑返回: 刷新源列表
    LaunchedEffect(Unit) {
        navigator.resultsFor(entry.id).filter { it.key == RouteResults.BOOK_SOURCE_EDIT }.collect {
            viewModel.startRefreshList()
        }
    }
    // endregion

    // region 切源流程 (对照 Dialog.changeTo + changeSource 第 284-345 行)

    /**
     * 切源核心: 显示 WaitDialog -> getToc -> 成功 pop(payload) / 失败 AppLog。
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
                navigator.pop(RouteResultPayload.ChangeSource(source, targetBook, toc))
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
                navigator.pop(RouteResultPayload.ChangeSource(source, newBook, toc))
            }
        }
    }
    // endregion

    // region Actions 实现

    val actions = object : ChangeSourceUiActions {
        override fun onBack() {
            navigator.pop()
        }

        override fun onStartStop() {
            viewModel.startOrStopSearch()
        }

        override fun onScreen(key: String) {
            viewModel.screen(key)
        }

        override fun onSearchModeChange(enabled: Boolean) {
            // searchMode 为 Screen 本地 rememberSaveable 状态, 此回调无额外动作
            // (对照 app 端 onSearchModeChange 仅赋值本地 searchMode)
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
            navigator.push(AppRoute.BookSourceManage)
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
            navigator.pop()
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
            navigator.push(AppRoute.BookSourceEdit(book.origin), RouteResults.BOOK_SOURCE_EDIT)
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
        onShowGroupPicker = { showGroupPicker = true },
        showGroupPicker = showGroupPicker,
        onGroupPickerDismiss = { showGroupPicker = false },
        onGroupPickerSelect = { group ->
            // 对照 app 端 onGroupSelected (第 272-282 行)
            showGroupPicker = false
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
