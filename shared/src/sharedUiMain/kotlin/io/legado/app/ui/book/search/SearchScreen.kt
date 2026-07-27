package io.legado.app.ui.book.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.component.AppFilletTextButton
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.platformStatusBarPadding
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.toTimeAgo
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 搜索界面导航回调 (KMP 版)。
 *
 * 原 app 端由 `SearchActivity` 直接 `startActivity<X>()` 跳转, 下沉后改为回调注入,
 * 由宿主 (app Activity / desktop 窗口) 实现具体跳转。
 */
interface SearchNavCallbacks {

    /** 返回 (标题栏返回箭头 / 系统返回手势)。 */
    fun onBack()

    /** 点击书籍 (补 notShelf type 后进详情, 宿主实现跳转)。 */
    fun onBookClick(book: BaseBook, longClick: Boolean = false)

    /** 进入书源管理页。 */
    fun onManageBookSources()

    /** 弹出搜索范围对话框 (分组/书源选择)。 */
    fun onAlertSearchScope()

    /** 弹出书源过滤规则列表对话框。 */
    fun onShowSourceFilterRule()

    /** 弹出应用日志对话框。 */
    fun onShowAppLog()

    /**
     * 清空搜索历史 (宿主实现, 通常弹确认对话框)。
     *
     * 原 app 端 [SearchActivity.alertClearHistory] 弹 `R.string.sure_clear_search_history` 确认框,
     * 用户确认后调 `viewModel.clearHistory()`。下沉后由宿主决定是否弹确认。
     * 默认实现直接清空 (桌面端验证场景), app 端 override 弹确认框保留原交互。
     */
    fun onClearHistory()
}

/**
 * 默认空实现: 桌面端验证场景使用, 所有回调 no-op。
 * (app 端实现自己的 SearchNavCallbacks 跳转到对应 Activity)
 */
object NoOpSearchNavCallbacks : SearchNavCallbacks {
    override fun onBack() {}
    override fun onBookClick(book: BaseBook, longClick: Boolean) {}
    override fun onManageBookSources() {}
    override fun onAlertSearchScope() {}
    override fun onShowSourceFilterRule() {}
    override fun onShowAppLog() {}
    // 桌面端验证场景: 清空历史 no-op (原 shared InputHelp 直接 viewModel.clearHistory()
    // 的行为改由宿主决定, app 端 override 弹确认框保留原交互)
    override fun onClearHistory() {}
}

/**
 * 搜索界面 Composable (KMP 版)。
 *
 * 下沉自 app 端 `SearchScreen(activity: SearchActivity)`, 替换:
 * - `SearchActivity` 持有状态 → [viewModel] StateFlow 收集 (collectAsState)
 * - `activity.startActivity<X>` → [navCallbacks] 接口回调
 * - `stringResource(R.string.xxx)` → [rememberString]("xxx") (Android actual 动态查 R.string, 桌面返回 key)
 * - `painterResource(R.drawable.xxx)` → [rememberPainter]("xxx")
 * - `colorResource(R.color.xxx)` → [AppTheme.colors] 语义色
 * - `WindowInsets.navigationBars.asPaddingValues()` → `platformStatusBarPadding()` (跨平台状态栏 padding)
 * - `ShelfCover` (app 专属, 走 Glide) → 简化文本项 (跨平台无 Glide, KMP 封面留后续 KP3)
 * - `KindLabels` / `UnreadBadge` (app 专属) → 简化为多源计数文字
 * - `AndroidView { LinearLayout + setUpExploreOptions }` (单源搜索选项 chip) → 暂未实现 (KMP 无桥接)
 * - `AndroidView { ItemExploreVideoBinding }` (视频卡) → 暂未实现 (KMP 无 ViewBinding)
 *
 * @param viewModel KMP 版 [SearchViewModel]
 * @param navCallbacks 路由回调, 默认 [NoOpSearchNavCallbacks]
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    navCallbacks: SearchNavCallbacks = NoOpSearchNavCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors

    // 收集 UI 状态
    val query by viewModel.query.collectAsState()
    val inputHelpVisible by viewModel.inputHelpVisible.collectAsState()
    val historyKeys by viewModel.historyKeys.collectAsState()
    val bookshelfBooks by viewModel.bookshelfBooks.collectAsState()
    val resultBooks by viewModel.searchBooks.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val focusEpoch by viewModel.focusEpoch.collectAsState()
    val scopeVersion by viewModel.scopeVersion.collectAsState()
    val bookshelfVersion by viewModel.bookshelfVersion.collectAsState()
    val precisionSearch by viewModel.precisionSearch.collectAsState()
    val searchFinishEmpty by viewModel.searchFinishEmpty.collectAsState()

    // 搜索布局: 低 4 位=列数 (0/1 单列; 2..6 N 列网格), bit 4 (0x10)=视频标志
    val searchStyle = AppConfigProviders.get().searchLayout
    val styleCols = BookSource.exploreStyleCols(searchStyle)
    val styleIsVideo = BookSource.exploreStyleIsVideo(searchStyle)
    val spanCount = if (styleCols <= 1) 1 else styleCols

    // 搜索结果为空时弹出"切换全部/关闭精准"对话框 (对齐原 observeLiveBus searchFinishLiveData)
    val pendingEmptyScope = remember(searchFinishEmpty) {
        searchFinishEmpty == true && !viewModel.searchScope.isAll()
    }
    if (pendingEmptyScope) {
        SearchEmptyAlertDialog(
            scopeDisplay = viewModel.searchScope.display,
            precision = precisionSearch,
            onSwitchAll = {
                viewModel.onSearchEmptyConfirmSwitchAll()
            },
            onDisablePrecision = {
                viewModel.onSearchEmptyConfirmDisablePrecision()
            },
            onDismiss = {
                // 仅消费一次, 重新触发 search 把 searchFinishEmpty 置 null
                viewModel.search("")
            },
        )
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        AppTitleBar(
            title = "",
            onBack = navCallbacks::onBack,
            titleContent = {
                SearchField(
                    query = query,
                    onQueryChange = viewModel::onQueryChange,
                    onQuerySubmit = viewModel::onQuerySubmit,
                    focusEpoch = focusEpoch,
                    onCleared = { viewModel.showInputHelp(true) },
                    modifier = Modifier.weight(1f),
                )
            },
            actions = {
                SearchActions(
                    viewModel = viewModel,
                    navCallbacks = navCallbacks,
                    scopeVersion = scopeVersion,
                    precisionSearch = precisionSearch,
                )
            },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(Modifier.fillMaxSize()) {
                if (inputHelpVisible) {
                    InputHelp(
                        viewModel = viewModel,
                        navCallbacks = navCallbacks,
                        bookshelfBooks = bookshelfBooks,
                        historyKeys = historyKeys,
                        spanCount = spanCount,
                        styleIsVideo = styleIsVideo,
                        styleCols = styleCols,
                    )
                } else {
                    ResultArea(
                        viewModel = viewModel,
                        books = resultBooks,
                        isSearching = isSearching,
                        hasMore = hasMore,
                        spanCount = spanCount,
                        styleIsVideo = styleIsVideo,
                        styleCols = styleCols,
                        bookshelfVersion = bookshelfVersion,
                    )
                }
            }
            RefreshBar(isSearching, Modifier.align(Alignment.TopStart))
            StartStopFab(
                visible = isSearching || (hasMore && viewModel.searchKey.isNotEmpty()),
                showStop = isSearching,
                onClick = viewModel::onFabClick,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

// ===== 搜索框 =====

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onQuerySubmit: () -> Unit,
    focusEpoch: Int,
    onCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) {
        onDispose {
            // 离开搜索框时清焦点收键盘
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    // receiptIntent 无 key 时请求焦点
    LaunchedEffect(focusEpoch) {
        if (focusEpoch > 0) runCatching { focusRequester.requestFocus() }
    }
    AppSearchField(
        value = query,
        onValueChange = onQueryChange,
        hint = rememberString("search_book_key"),
        onSearch = onQuerySubmit,
        modifier = modifier,
        textFieldModifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                // 失焦时收起输入帮助区 (与原 onFieldFocusChanged 简化对齐)
                if (!it.isFocused && it.hasFocus == false) {
                    onCleared()
                }
            },
    )
}

// ===== 菜单 (对齐 book_search.xml + onMenuOpened 动态范围组) =====

@Composable
private fun SearchActions(
    viewModel: SearchViewModel,
    navCallbacks: SearchNavCallbacks,
    scopeVersion: Int,
    precisionSearch: Boolean,
) {
    OverflowMenu { dismiss ->
        // 触发重组读取最新 scope
        @Suppress("UNUSED_EXPRESSION") scopeVersion
        val scope = viewModel.searchScope
        val names = if (scope.isSource()) {
            scope.displayNames.take(1)
        } else {
            scope.displayNames
        }
        names.forEach { name ->
            CheckMenuItem(name, checked = true) { dismiss(); viewModel.removeScopeName(name) }
        }
        CheckMenuItem(rememberString("all_source"), checked = names.isEmpty()) {
            dismiss(); viewModel.selectScopeAll()
        }
        CheckMenuItem(rememberString("precision_search"), precisionSearch) {
            dismiss(); viewModel.togglePrecisionSearch()
        }
        TextMenuItem(rememberString("book_source_manage")) {
            dismiss(); navCallbacks.onManageBookSources()
        }
        TextMenuItem(rememberString("groups_or_source")) {
            dismiss(); navCallbacks.onAlertSearchScope()
        }
        TextMenuItem(rememberString("source_filter_rule")) {
            dismiss(); navCallbacks.onShowSourceFilterRule()
        }
        TextMenuItem(rememberString("log")) { dismiss(); navCallbacks.onShowAppLog() }
    }
}

@Composable
private fun TextMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = AppTheme.colors.primaryText) },
        onClick = onClick,
    )
}

@Composable
private fun CheckMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    DropdownMenuItem(
        text = { Text(text, color = colors.primaryText) },
        trailingIcon = { AppMenuCheckbox(checked = checked) },
        onClick = onClick,
    )
}

// ===== 输入帮助: 书架命中区与历史词互斥 =====

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.InputHelp(
    viewModel: SearchViewModel,
    navCallbacks: SearchNavCallbacks,
    bookshelfBooks: List<io.legado.app.data.entities.Book>,
    historyKeys: List<SearchKeyword>,
    spanCount: Int,
    styleIsVideo: Boolean,
    styleCols: Int,
) {
    val colors = AppTheme.colors
    val navPad = Modifier.platformStatusBarPadding()
    if (bookshelfBooks.isNotEmpty()) {
        Text(
            text = rememberString("bookshelf"),
            color = colors.primaryText,
            fontSize = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        // 书架命中区 (单列展示, KMP 简化版无 ShelfCover)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(bookshelfBooks, key = { it.bookUrl }) { book ->
                SearchListItem(
                    book = book,
                    coverPath = book.coverUrl,
                    isVideoStyle = styleCols == 0 && styleIsVideo,
                    inBookshelf = true,
                    showShelfDot = false,
                    originCount = 0,
                    readTitle = book.durChapterTitle,
                    timeAgo = book.durChapterTime.toTimeAgo(),
                    intro = book.intro,
                    onClick = { viewModel.searchHistory(book.name) },
                    onLongClick = { viewModel.searchHistory(book.name) },
                )
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = rememberString("searchHistory"),
                color = colors.primaryText,
                fontSize = 14.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp),
            )
            if (historyKeys.isNotEmpty()) {
                Text(
                    text = rememberString("clear"),
                    color = colors.primaryText,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { navCallbacks.onClearHistory() }
                        .padding(8.dp),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
                .then(navPad),
        ) {
            FlowRow(Modifier.fillMaxWidth()) {
                historyKeys.forEach { keyword ->
                    AppFilletTextButton(
                        text = keyword.word,
                        onLongClick = { viewModel.deleteHistory(keyword) },
                        onClick = { viewModel.searchHistory(keyword.word) },
                    )
                }
            }
        }
    }
}

// ===== 结果列表 =====

@Composable
private fun ColumnScope.ResultArea(
    viewModel: SearchViewModel,
    books: List<SearchBook>,
    isSearching: Boolean,
    hasMore: Boolean,
    spanCount: Int,
    styleIsVideo: Boolean,
    styleCols: Int,
    bookshelfVersion: Int,
) {
    @Suppress("UNUSED_EXPRESSION") bookshelfVersion // 书架增删时重组刷新绿点
    if (spanCount == 1) {
        val state = rememberLazyListState()
        // 新批次插到头部时回顶 (对齐原 AdapterDataObserver 的 positionStart==0)
        val firstKey = books.firstOrNull()?.let { "${it.name}|${it.author}" }
        LaunchedEffect(firstKey) { if (firstKey != null) state.scrollToItem(0) }
        // 触底续搜 (对齐原 canScrollVertically(1)==false -> scrollToBottom)
        LaunchedEffect(state) {
            snapshotFlow { state.layoutInfo.totalItemsCount to state.canScrollForward }
                .distinctUntilChanged()
                .collect { (count, forward) ->
                    if (count > 0 && !forward) viewModel.scrollToBottom()
                }
        }
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(books, key = { "${it.name}|${it.author}" }) { book ->
                SearchListItem(
                    book = book,
                    coverPath = book.coverUrl,
                    isVideoStyle = styleCols == 0 && styleIsVideo,
                    inBookshelf = viewModel.isInBookShelf(book),
                    showShelfDot = true,
                    originCount = book.origins.size,
                    intro = book.intro,
                    onClick = { viewModel.searchHistory(book.name) },
                    onLongClick = { viewModel.searchHistory(book.name) },
                )
            }
        }
    } else {
        val state = rememberLazyGridState()
        val firstKey = books.firstOrNull()?.let { "${it.name}|${it.author}" }
        LaunchedEffect(firstKey) { if (firstKey != null) state.scrollToItem(0) }
        LaunchedEffect(state) {
            snapshotFlow { state.layoutInfo.totalItemsCount to state.canScrollForward }
                .distinctUntilChanged()
                .collect { (count, forward) ->
                    if (count > 0 && !forward) viewModel.scrollToBottom()
                }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(spanCount),
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(books, key = { "${it.name}|${it.author}" }) { book ->
                SearchGridItem(
                    book = book,
                    inBookshelf = viewModel.isInBookShelf(book),
                    showBadge = true,
                    onClick = { viewModel.searchHistory(book.name) },
                    onLongClick = { viewModel.searchHistory(book.name) },
                )
            }
        }
    }
}

// ===== 条目 (List tier, KMP 简化版: 无 ShelfCover/KindLabels/UnreadBadge) =====

@Composable
private fun SearchListItem(
    book: BaseBook,
    coverPath: String?,
    isVideoStyle: Boolean,
    inBookshelf: Boolean,
    showShelfDot: Boolean,
    originCount: Int,
    intro: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    readTitle: String? = null,
    timeAgo: String? = null,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
    ) {
        // KMP 简化版封面: 用纯色 Box 占位 (ShelfCover 走 Glide 未下沉, KP3 再补)
        // 0.75f: 16:9 视频封面按 3/4 高度收窄, 对齐 applyCoverHeight
        val coverHeight = AppConfigProviders.get().bookshelfCoverHeight
            .let { if (isVideoStyle) (it * 0.75f).toInt() else it }
        Box(
            Modifier
                .size(width = (coverHeight * 0.75f).dp, height = coverHeight.dp)
                .background(colors.bottomBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (!coverPath.isNullOrBlank()) {
                Text(
                    text = book.name.take(2),
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .heightIn(min = coverHeight.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showShelfDot && inBookshelf) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(colors.accent, CircleShape),
                    )
                }
                Text(
                    text = book.name,
                    color = colors.primaryText,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
                // 多源计数 (替代原 UnreadBadge, KMP 简化为文字)
                if (originCount > 1) {
                    Text(
                        text = "$originCount",
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Text(
                text = book.getRealAuthor(),
                color = colors.secondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val kinds = book.getKindList()
            if (kinds.isNotEmpty()) {
                Text(
                    text = kinds.joinToString(" "),
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!readTitle.isNullOrEmpty()) {
                Text(
                    text = readTitle,
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!book.latestChapterTitle.isNullOrEmpty()) {
                Text(
                    text = book.latestChapterTitle.toString(),
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!intro.isNullOrBlank()) {
                Text(
                    text = intro,
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
            if (timeAgo != null) {
                Text(
                    text = timeAgo,
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Grid tier: 封面占位 + 两行书名 (KMP 简化版, 无 ShelfCover/书签图) */
@Composable
private fun SearchGridItem(
    book: BaseBook,
    inBookshelf: Boolean,
    showBadge: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Box(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(Modifier.fillMaxWidth()) {
            // KMP 简化版封面: 纯色 Box 占位
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp)
                    .aspectRatio(0.75f)
                    .background(colors.bottomBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = book.name.take(2),
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = book.name,
                color = colors.primaryText,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
            )
        }
        // 书架书签: 简化为 accent 圆点 (替代原 ic_bookmark 图)
        if (showBadge && inBookshelf) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 12.dp)
                    .size(8.dp)
                    .background(colors.accent, CircleShape),
            )
        }
    }
}

// ===== 进度条与启停按钮 =====

@Composable
private fun RefreshBar(visible: Boolean, modifier: Modifier) {
    if (!visible) return
    val colors = AppTheme.colors
    if (LocalEInk.current) {
        Box(
            modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.accent),
        )
    } else {
        LinearProgressIndicator(
            color = colors.accent,
            trackColor = Color.Transparent,
            modifier = modifier
                .fillMaxWidth()
                .height(2.dp),
        )
    }
}

@Composable
private fun StartStopFab(
    visible: Boolean,
    showStop: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    if (!visible) return
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = if (pressed) {
        Color(ColorUtils.darkenColor(colors.accent.toArgb()))
    } else {
        colors.accent
    }
    val tint = if (ColorUtils.isColorLight(colors.accent.toArgb())) Color.Black else Color.White
    Box(
        modifier
            .padding(16.dp)
            .shadow(6.dp, CircleShape)
            .size(32.dp)
            .background(bg, CircleShape)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter(if (showStop) "ic_stop_black_24dp" else "ic_play_24dp"),
            contentDescription = rememberString("stop"),
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

// ===== 搜索结果为空对话框 (对齐原 observeLiveBus searchFinishLiveData) =====

@Composable
private fun SearchEmptyAlertDialog(
    scopeDisplay: String,
    precision: Boolean,
    onSwitchAll: () -> Unit,
    onDisablePrecision: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = if (precision) {
        rememberString("search_result_empty_close_precision", scopeDisplay)
    } else {
        rememberString("search_result_empty_switch_all", scopeDisplay)
    }
    io.legado.app.ui.compose.component.AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("search_result_empty"),
        message = message,
        okButton = io.legado.app.ui.compose.component.AlertButton(
            text = rememberString("ok"),
            onClick = if (precision) onDisablePrecision else onSwitchAll,
        ),
        cancelButton = io.legado.app.ui.compose.component.AlertButton(
            text = rememberString("cancel"),
            onClick = null,
        ),
    )
}
