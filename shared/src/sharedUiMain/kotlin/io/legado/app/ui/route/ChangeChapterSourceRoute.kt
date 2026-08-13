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
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
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
import io.legado.app.utils.throttleLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.no
import legado.shared.generated.resources.search_result_empty
import legado.shared.generated.resources.yes
import org.jetbrains.compose.resources.stringResource

/**
 * 章节换源 shared 路由入口。
 * 通过 [ChangeChapterSourceContent] 复用换源屏幕, 本路由负责导航语义 (回传章节正文 / 整书换源)。
 */
@Composable
fun ChangeChapterSourceRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.ChangeChapterSource
    val book = remember(route) { route.book.asBook() }
    ChangeChapterSourceContent(
        book = book,
        chapterIndex = route.chapterIndex,
        chapterTitle = route.chapterTitle,
        onBack = { navigator.pop() },
        onChapterChanged = { content ->
            navigator.pop(RouteResultPayload.ChangeChapterContent(content))
        },
        onSourceChanged = { source, newBook, toc ->
            navigator.pop(RouteResultPayload.ChangeSource(source, newBook, toc))
        },
        onEditSource = { origin ->
            navigator.push(AppRoute.BookSourceEdit(origin), RouteResults.BOOK_SOURCE_EDIT)
        },
        onBookSourceManage = { navigator.push(AppRoute.BookSourceManage) },
        bookSourceEditFlow = navigator.resultsFor(entry.id)
            .filter { it.key == RouteResults.BOOK_SOURCE_EDIT },
    )
}

/**
 * 章节换源弹窗形态 (对照原版 ChangeChapterSourceDialog: 全高底部弹窗)。
 * 由阅读页"换源"图标长按弹起; 选章成功后经 [onChapterChanged] 回传正文 (宿主负责落库刷新)。
 */
@Composable
fun ChangeChapterSourceDialogHost(
    book: Book,
    chapterIndex: Int,
    chapterTitle: String,
    onChapterChanged: (content: String) -> Unit,
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
                ChangeChapterSourceContent(
                    book = book,
                    chapterIndex = chapterIndex,
                    chapterTitle = chapterTitle,
                    onBack = onDismiss,
                    onChapterChanged = { content ->
                        onDismiss()
                        onChapterChanged(content)
                    },
                    onSourceChanged = { source, newBook, toc ->
                        onDismiss()
                        onSourceChanged(source, newBook, toc)
                    },
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
 * 章节换源屏幕共享正文 (路由/弹窗两形态共用)。
 *
 * 接线对照 app 端 [io.legado.app.ui.book.changesource.ChangeChapterSourceDialog]:
 * 初始化 / searchDataFlow / searchState / flowEnabledGroups / searchFinishCallback /
 * openToc / clickChapter / deleteSource 全部保留, 导航动作经回调外抛。
 *
 * @param book 当前书籍
 * @param chapterIndex 当前章节序号
 * @param chapterTitle 当前章节标题
 * @param onBack 返回 (路由=pop, 弹窗=dismiss)
 * @param onChapterChanged 章节正文获取成功 (content)
 * @param onSourceChanged 删除当前源后自动整书换源 (source, newBook, toc)
 * @param onEditSource 编辑书源 (origin)
 * @param onBookSourceManage 打开书源管理
 * @param bookSourceEditFlow 书源编辑返回事件流 (路由形态传入, 弹窗形态已 dismiss 无需刷新)
 */
@Composable
fun ChangeChapterSourceContent(
    book: Book,
    chapterIndex: Int,
    chapterTitle: String,
    onBack: () -> Unit,
    onChapterChanged: (content: String) -> Unit,
    onSourceChanged: (source: BookSource, newBook: Book, toc: List<BookChapter>) -> Unit,
    onEditSource: (origin: String) -> Unit,
    onBookSourceManage: () -> Unit,
    bookSourceEditFlow: Flow<RouteResult>? = null,
) {
    val scope = rememberCoroutineScope()
    val platform = ChangeBookSourcePlatformProviders.get()
    val viewModel = remember(book.bookUrl) {
        ChangeBookSourceViewModelShared(scope = scope, platform = platform)
    }
    // 释放搜索线程池 (对照 app 端 ViewModel.onCleared)
    DisposableEffect(viewModel) {
        onDispose { viewModel.onCleared() }
    }

    val screenModel = remember(book.bookUrl) { ChangeChapterSourceScreenModel() }
    val state by screenModel.state.collectAsState()

    // 当前点击的源条目 (openToc 后用于 clickChapter 取 book 给 getContent)
    var currentSearchBook by remember { mutableStateOf<SearchBook?>(null) }
    // 空结果 alert 显示状态 (searchFinishCallback 触发)
    var showEmptyGroupAlert by remember { mutableStateOf(false) }

    // 1. 初始化数据 (对照 Dialog.onViewCreated 第 111-128 行)
    // 键用 bookUrl (Book.equals 已改全字段语义, 整对象键会在进度等字段变化时误重跑 initData)
    LaunchedEffect(book.bookUrl) {
        viewModel.initData(
            name = book.name,
            author = book.author,
            fromReadBookActivity = false,
            oldBook = book,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
        )
        screenModel.dispatch(ChangeChapterSourceUiEvent.BookInitialized(book))
        screenModel.dispatch(
            ChangeChapterSourceUiEvent.ChapterInfoUpdated(chapterTitle, chapterIndex)
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

    // 书源编辑返回: 刷新源列表 (弹窗形态传 null, 编辑前已 dismiss)
    if (bookSourceEditFlow != null) {
        LaunchedEffect(bookSourceEditFlow) {
            bookSourceEditFlow.collect { viewModel.startRefreshList() }
        }
    }

    // 7. ChangeChapterSourceUiActions
    val actions = object : ChangeChapterSourceUiActions {
        override fun onBack() {
            onBack()
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
            onBookSourceManage()
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
            onEditSource(book.origin)
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
                        onSourceChanged(source, b, toc)
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
                    onChapterChanged(content)
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
        message = stringResource(Res.string.loading),
        onDismissRequest = { },
    )

    // 13. 空结果 alert (对照 Dialog.searchFinishCallback 第 96-106 行)
    if (showEmptyGroupAlert) {
        AppAlertDialog(
            onDismissRequest = { showEmptyGroupAlert = false },
            title = stringResource(Res.string.search_result_empty),
            message = "${state.searchGroup}分组搜索结果为空,是否切换到全部分组",
            okButton = AlertButton(text = stringResource(Res.string.yes)) {
                showEmptyGroupAlert = false
                platform.searchGroup = ""
                screenModel.dispatch(ChangeChapterSourceUiEvent.SearchGroupChanged(""))
                viewModel.startSearch()
            },
            cancelButton = AlertButton(text = stringResource(Res.string.no)) {
                showEmptyGroupAlert = false
            },
        )
    }
}
