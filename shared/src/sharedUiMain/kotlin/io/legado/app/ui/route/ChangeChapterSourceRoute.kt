package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.book.changesource.ChangeBookSourcePlatformProviders
import io.legado.app.ui.book.changesource.ChangeBookSourceViewModelShared
import io.legado.app.ui.book.changesource.ChangeChapterSourceItemActions
import io.legado.app.ui.book.changesource.ChangeChapterSourceMenuActions
import io.legado.app.ui.book.changesource.ChangeChapterSourceScreen
import io.legado.app.ui.book.changesource.ChangeChapterSourceScreenModel
import io.legado.app.ui.book.changesource.ChangeChapterSourceUiActions
import io.legado.app.ui.book.changesource.ChangeChapterSourceUiEvent
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.throttleLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * 章节换源 shared 路由入口。
 *
 * 通过 [ScreenModelStore] 复用 [ChangeChapterSourceScreenModel], 渲染 [ChangeChapterSourceScreen]。
 *
 * 与 [ChangeSourceRoute] (整书换源) 不同: 章节换源仅替换当前章节来源, 不影响整书。
 * 平台专属能力 (搜索 startOrStopSearch / 筛选 screen / 取目录 getToc + toc 预览覆盖层 /
 * 取正文 getContent) 通过 [ChangeBookSourceViewModelShared] (已下沉到 commonMain) +
 * [ChangeBookSourcePlatformProviders] 注入平台实现完成。
 *
 * 接线对照 app 端 [io.legado.app.ui.book.changesource.ChangeChapterSourceDialog]:
 * - 初始化 (Dialog.onViewCreated 第 111-128 行): initData 6 参数 + dispatch UI 状态;
 * - searchDataFlow / searchState / flowEnabledGroups 桥接 (Dialog.Content 第 153-161 行);
 * - searchFinishCallback 空结果 alert (Dialog.searchFinishCallback 第 92-109 行);
 * - onItemClick openToc 流程 (Dialog.openToc 第 291-306 行);
 * - onClickChapter getContent 流程 (Dialog.clickChapter 第 308-317 行);
 * - onGroupPickerSelect 切换分组 (Dialog.onGroupSelected 第 279-289 行);
 * - onDelete 删除当前源 fallback 整书换源 (Dialog.deleteSource 第 325-332 行);
 * - 菜单 6 项 (Dialog.Content 第 181-212 行, 不含 RefreshList / Close)。
 *
 * onSearchModeChange 为 Screen 本地状态回调, app 端 Dialog 也仅赋值本地 searchMode, 故空实现等价。
 */
@Composable
fun ChangeChapterSourceRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.ChangeChapterSource
    val scope = rememberCoroutineScope()
    val platform = ChangeBookSourcePlatformProviders.get()
    val viewModel = remember(entry) {
        ChangeBookSourceViewModelShared(scope = scope, platform = platform)
    }

    val screenModel = screenModelStore.getOrCreateTyped(entry) { ChangeChapterSourceScreenModel() }
    val state by screenModel.state.collectAsState()

    // 当前点击的源条目 (openToc 后用于 clickChapter 取 book 给 getContent)
    var currentSearchBook by remember { mutableStateOf<SearchBook?>(null) }
    // 分组选择对话框显示状态
    var showGroupPicker by remember { mutableStateOf(false) }
    // 空结果 alert 显示状态 (searchFinishCallback 触发)
    var showEmptyGroupAlert by remember { mutableStateOf(false) }

    // 1. 初始化数据 (对照 Dialog.onViewCreated 第 111-128 行)
    LaunchedEffect(route.book) {
        val book = route.book.asBook()
        viewModel.initData(
            name = book.name,
            author = book.author,
            fromReadBookActivity = false,
            oldBook = book,
            chapterIndex = route.chapterIndex,
            chapterTitle = route.chapterTitle,
        )
        screenModel.dispatch(ChangeChapterSourceUiEvent.BookInitialized(book))
        screenModel.dispatch(
            ChangeChapterSourceUiEvent.ChapterInfoUpdated(route.chapterTitle, route.chapterIndex)
        )
        screenModel.dispatch(ChangeChapterSourceUiEvent.CurBookUrlChanged(book.bookUrl))
        // 同步 platform 4 个开关 + searchGroup 到 UI 状态
        screenModel.dispatch(
            ChangeChapterSourceUiEvent.CheckAuthorChanged(platform.changeSourceCheckAuthor)
        )
        screenModel.dispatch(
            ChangeChapterSourceUiEvent.LoadInfoChanged(platform.changeSourceLoadInfo)
        )
        screenModel.dispatch(
            ChangeChapterSourceUiEvent.LoadTocChanged(platform.changeSourceLoadToc)
        )
        screenModel.dispatch(
            ChangeChapterSourceUiEvent.LoadWordCountChanged(platform.changeSourceLoadWordCount)
        )
        screenModel.dispatch(
            ChangeChapterSourceUiEvent.SearchGroupChanged(platform.searchGroup)
        )
    }

    // 3. 桥接 searchDataFlow (对照 Dialog.Content 第 153-156 行)
    LaunchedEffect(Unit) {
        viewModel.searchDataFlow.throttleLatest(1_000).collect { sources ->
            screenModel.dispatch(ChangeChapterSourceUiEvent.SourcesLoaded(sources))
        }
    }

    // 4. 桥接 searchState
    LaunchedEffect(Unit) {
        viewModel.searchState.collect { searching ->
            screenModel.dispatch(ChangeChapterSourceUiEvent.LoadingChanged(searching))
        }
    }

    // 5. 桥接 flowEnabledGroups (对照 Dialog.Content 第 157-161 行)
    LaunchedEffect(Unit) {
        AppDbProviders.get().bookSourceDao.flowEnabledGroups().conflate().collect { groups ->
            screenModel.dispatch(ChangeChapterSourceUiEvent.GroupsLoaded(groups))
        }
    }

    // 6. 设置 searchFinishCallback (对照 Dialog.searchFinishCallback 第 92-109 行)
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

    // 7. ChangeChapterSourceUiActions
    val actions = object : ChangeChapterSourceUiActions {
        override fun onBack() {
            navigator.pop()
        }

        override fun onStartStop() {
            viewModel.startOrStopSearch()
        }

        override fun onScreen(key: String) {
            viewModel.screen(key)
        }

        // searchMode 为 Screen 本地 rememberSaveable 状态, 此回调无额外动作
        override fun onSearchModeChange(enabled: Boolean) {}

        // 对照 Dialog.openToc 第 291-306 行
        override fun onItemClick(book: SearchBook) {
            currentSearchBook = book
            screenModel.dispatch(ChangeChapterSourceUiEvent.TocListLoaded(null))
            screenModel.dispatch(ChangeChapterSourceUiEvent.TocVisibleChanged(true))
            screenModel.dispatch(ChangeChapterSourceUiEvent.TocLoadingChanged(true))
            val bookEntity = book.toBook()
            viewModel.getToc(
                bookEntity,
                { toc, _ ->
                    // platform.getDurChapter 用 oldBook 版本 (BookHelp.getDurChapter(oldBook, chapters))
                    val dur = state.book?.let { platform.getDurChapter(it, toc) } ?: 0
                    screenModel.dispatch(ChangeChapterSourceUiEvent.DurChapterIndexChanged(dur))
                    screenModel.dispatch(ChangeChapterSourceUiEvent.TocLoadingChanged(false))
                    screenModel.dispatch(ChangeChapterSourceUiEvent.TocListLoaded(toc))
                },
                { e ->
                    screenModel.dispatch(ChangeChapterSourceUiEvent.TocVisibleChanged(false))
                    AppLog.put("单章换源获取目录出错\n$e", e, true)
                },
            )
        }
    }

    // 8. ChangeChapterSourceMenuActions (对照 Dialog.Content 第 181-212 行菜单)
    val menuActions = object : ChangeChapterSourceMenuActions {
        override fun onBookSourceManage() {
            navigator.push(AppRoute.BookSourceManage)
        }

        override fun onCheckAuthorChange(value: Boolean) {
            platform.changeSourceCheckAuthor = value
            screenModel.dispatch(ChangeChapterSourceUiEvent.CheckAuthorChanged(value))
            viewModel.refresh()
        }

        override fun onLoadWordCountChange(value: Boolean) {
            platform.changeSourceLoadWordCount = value
            screenModel.dispatch(ChangeChapterSourceUiEvent.LoadWordCountChanged(value))
            viewModel.onLoadWordCountChecked(value)
        }

        override fun onLoadInfoChange(value: Boolean) {
            platform.changeSourceLoadInfo = value
            screenModel.dispatch(ChangeChapterSourceUiEvent.LoadInfoChanged(value))
        }

        override fun onLoadTocChange(value: Boolean) {
            platform.changeSourceLoadToc = value
            screenModel.dispatch(ChangeChapterSourceUiEvent.LoadTocChanged(value))
        }
    }

    // 9. ChangeChapterSourceItemActions
    val itemActions = object : ChangeChapterSourceItemActions {
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
            navigator.push(AppRoute.BookSourceEdit(book.origin))
        }

        override fun onDisable(book: SearchBook) {
            viewModel.disableSource(book)
        }

        // 对照 Dialog.deleteSource 第 325-332 行
        override fun onDelete(book: SearchBook) {
            viewModel.del(book)
            if (state.curBookUrl == book.bookUrl) {
                state.book?.let { oldBook ->
                    viewModel.autoChangeSource(oldBook.type) { b, toc, source ->
                        navigator.pop(RouteResultPayload.ChangeSource(source, b, toc))
                    }
                }
            }
        }
    }

    // 10. onClickChapter (对照 Dialog.clickChapter 第 308-317 行)
    val onClickChapter: (BookChapter, String?) -> Unit = { chapter, nextChapterUrl ->
        currentSearchBook?.let { sb ->
            screenModel.dispatch(ChangeChapterSourceUiEvent.TocLoadingChanged(true))
            viewModel.getContent(
                sb.toBook(),
                chapter,
                nextChapterUrl,
                { content ->
                    navigator.pop(RouteResultPayload.ChangeChapterContent(content))
                },
                { msg ->
                    screenModel.dispatch(ChangeChapterSourceUiEvent.TocLoadingChanged(false))
                    screenModel.dispatch(ChangeChapterSourceUiEvent.TocVisibleChanged(false))
                    platform.toastOnUi(msg)
                },
            )
        }
    }

    // 11. onGroupPickerSelect (对照 Dialog.onGroupSelected 第 279-289 行)
    val onGroupPickerSelect: (String) -> Unit = { group ->
        if (group != platform.searchGroup) {
            platform.searchGroup = group
            screenModel.dispatch(ChangeChapterSourceUiEvent.SearchGroupChanged(group))
            scope.launch {
                viewModel.stopSearch()
                if (viewModel.refresh()) {
                    viewModel.startSearch()
                }
            }
        }
    }

    ChangeChapterSourceScreen(
        state = state,
        actions = actions,
        menuActions = menuActions,
        itemActions = itemActions,
        searchGroup = state.searchGroup,
        onSearchGroupChange = { /* 已在 onGroupPickerSelect 中 dispatch */ },
        onShowGroupPicker = { showGroupPicker = true },
        showGroupPicker = showGroupPicker,
        onGroupPickerDismiss = { showGroupPicker = false },
        onGroupPickerSelect = onGroupPickerSelect,
        groups = state.groups,
        onTocHide = {
            screenModel.dispatch(ChangeChapterSourceUiEvent.TocVisibleChanged(false))
        },
        onClickChapter = onClickChapter,
    )

    // 12. WaitDialog (toc 加载中, 对照 Dialog.tocLoading 显示)
    WaitDialog(
        visible = state.tocLoading && state.tocVisible,
        message = rememberString("loading"),
        onDismissRequest = { },
    )

    // 13. 空结果 alert (对照 Dialog.searchFinishCallback 第 96-106 行)
    if (showEmptyGroupAlert) {
        AppAlertDialog(
            onDismissRequest = { showEmptyGroupAlert = false },
            title = rememberString("search_result_empty"),
            message = "${state.searchGroup}分组搜索结果为空,是否切换到全部分组",
            okButton = AlertButton(text = rememberString("yes")) {
                showEmptyGroupAlert = false
                platform.searchGroup = ""
                screenModel.dispatch(ChangeChapterSourceUiEvent.SearchGroupChanged(""))
                viewModel.startSearch()
            },
            cancelButton = AlertButton(text = rememberString("no")) {
                showEmptyGroupAlert = false
            },
        )
    }
}
