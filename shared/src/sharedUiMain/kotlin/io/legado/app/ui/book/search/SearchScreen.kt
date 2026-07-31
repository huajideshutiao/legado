package io.legado.app.ui.book.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.ui.bookshelf.KindLabels
import io.legado.app.ui.bookshelf.LocalBookCoverSlot
import io.legado.app.ui.bookshelf.ShelfGridItem
import io.legado.app.ui.bookshelf.ShelfLastUpdateText
import io.legado.app.ui.bookshelf.ShelfListItem
import io.legado.app.ui.bookshelf.ShelfRowIcon
import io.legado.app.ui.bookshelf.ShelfVideoItem
import io.legado.app.ui.bookshelf.UnreadBadge
import io.legado.app.ui.bookshelf.toCoverBook
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppFilletTextButton
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.component.FastScrollLazyVerticalGrid
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.RadioChip
import io.legado.app.ui.compose.component.StrokeTextChip
import io.legado.app.ui.compose.component.rememberResponsiveColumns
import io.legado.app.ui.compose.platform.rememberNavigationBarPaddingValues
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.ColorUtils
import kotlinx.coroutines.flow.distinctUntilChanged
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.all_source
import legado.shared.generated.resources.book_source_manage
import legado.shared.generated.resources.bookshelf
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.clear
import legado.shared.generated.resources.groups_or_source
import legado.shared.generated.resources.intro_show_null
import legado.shared.generated.resources.log
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.precision_search
import legado.shared.generated.resources.search
import legado.shared.generated.resources.searchHistory
import legado.shared.generated.resources.search_book_key
import legado.shared.generated.resources.search_result_empty
import legado.shared.generated.resources.search_result_empty_close_precision
import legado.shared.generated.resources.search_result_empty_switch_all
import legado.shared.generated.resources.source_filter_rule
import legado.shared.generated.resources.stop
import org.jetbrains.compose.resources.stringResource

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
 * - `WindowInsets.navigationBars.asPaddingValues()` → `rememberNavigationBarPaddingValues()` (跨平台导航栏 padding)
 * - `ShelfCover` (app 专属) → [coverSlot] / [shelfCoverSlot] 注入, 未传时取 [LocalBookCoverSlot]
 * - `KindLabels` / `UnreadBadge` (app 专属) → 复用书架 shared 版同名组件
 * - `AndroidView { LinearLayout + setUpExploreOptions }` (单源搜索选项 chip) → 暂未实现 (KMP 无桥接)
 * - `AndroidView { ItemExploreVideoBinding }` (视频卡) → 暂未实现 (KMP 无 ViewBinding)
 *
 * @param viewModel KMP 版 [SearchViewModel]
 * @param navCallbacks 路由回调, 默认 [NoOpSearchNavCallbacks]
 * @param coverSlot 搜索结果封面注入 (list/grid 两档条目共用)。null 时取
 *   [LocalBookCoverSlot] (宿主端注入 ShelfCover, 兜底 SharedBookCover), 与书架/发现同源。
 *   isVideoCover 供各端选封面比例 (对照 CoverRatio: false=NOVEL, true=VIDEO)
 * @param shelfCoverSlot 输入帮助区书架命中项封面注入, 契约同 [coverSlot] (条目类型为 Book)
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    navCallbacks: SearchNavCallbacks = NoOpSearchNavCallbacks,
    modifier: Modifier = Modifier,
    coverSlot: (@Composable (SearchBook, Modifier, isVideoCover: Boolean) -> Unit)? = null,
    shelfCoverSlot: (@Composable (Book, Modifier, isVideoCover: Boolean) -> Unit)? = null,
) {
    val colors = AppTheme.colors

    // 封面 slot: 显式传入优先, 否则取 CompositionLocal (对照 BookshelfScreen / ExploreShowRoute)
    val bookCoverSlot = LocalBookCoverSlot.current
    val resolvedShelfCoverSlot = shelfCoverSlot ?: bookCoverSlot
    // 默认 lambda 只建一次: 每次重组换实例, 会让结果列表所有可见条目一起重组
    // (参照书架 BookshelfScreen.kt 的 bookCoverSlot 透传注释)
    val resolvedCoverSlot: @Composable (SearchBook, Modifier, Boolean) -> Unit =
        remember(coverSlot, bookCoverSlot, viewModel) {
            coverSlot ?: { book, coverModifier, isVideoCover ->
                // SearchBook → Book 适配 LocalBookCoverSlot 签名 (同 ExploreShowRoute);
                // 渲染时就按书架态打 notShelf 标记, 非书架书的封面才不会落进持久缓存区
                val inShelf = viewModel.isInBookShelf(book)
                bookCoverSlot(
                    remember(book, inShelf) { book.toCoverBook(inShelf) },
                    coverModifier,
                    isVideoCover,
                )
            }
        }

    // 收集 UI 状态
    val query by viewModel.query.collectAsState()
    val inputHelpVisible by viewModel.inputHelpVisible.collectAsState()
    val historyKeys by viewModel.historyKeys.collectAsState()
    val bookshelfBooks by viewModel.bookshelfBooks.collectAsState()
    val resultBooks by viewModel.searchBooks.collectAsState(initial = emptyList())
    val isSearching by viewModel.isSearching.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val focusEpoch by viewModel.focusEpoch.collectAsState()
    val scopeVersion by viewModel.scopeVersion.collectAsState()
    val bookshelfVersion by viewModel.bookshelfVersion.collectAsState()
    val precisionSearch by viewModel.precisionSearch.collectAsState()
    val manualStopped by viewModel.manualStopped.collectAsState()

    val searchOptionsVersion by viewModel.searchOptionsVersion.collectAsState()

    // 搜索布局: 低 4 位=列数 (0/1 单列; 2..6 N 列网格), bit 4 (0x10)=视频标志
    val searchStyle = AppConfigProviders.get().searchLayout
    val styleCols = BookSource.exploreStyleCols(searchStyle)
    val styleIsVideo = BookSource.exploreStyleIsVideo(searchStyle)
    val spanCount = if (styleCols <= 1) 1 else styleCols

    // 搜索结果为空时弹出"切换全部/关闭精准"对话框 (对齐原 searchFinishLiveData.observe)
    var pendingEmptyScope by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.searchFinishEmpty.collect { isEmpty ->
            pendingEmptyScope = isEmpty && !viewModel.searchScope.isAll()
        }
    }
    if (pendingEmptyScope) {
        SearchEmptyAlertDialog(
            scopeDisplay = viewModel.searchScope.display,
            precision = precisionSearch,
            onSwitchAll = {
                pendingEmptyScope = false
                viewModel.onSearchEmptyConfirmSwitchAll()
            },
            onDisablePrecision = {
                pendingEmptyScope = false
                viewModel.onSearchEmptyConfirmDisablePrecision()
            },
            onDismiss = { pendingEmptyScope = false },
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
                    onCleared = {
                        // 搜索中或已有结果时不弹回输入帮助 (对齐原 onFieldFocusChanged 的 autoLoading/有结果分支)
                        if (!viewModel.isSearching.value && resultBooks.isEmpty()) {
                            viewModel.showInputHelp(true)
                        }
                    },
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
                // 搜索中或已有结果时强制显示结果区, 不弹回书架/历史 (对齐原 onFieldFocusChanged)
                val showInputHelp = inputHelpVisible && !isSearching && resultBooks.isEmpty()
                if (showInputHelp) {
                    InputHelp(
                        viewModel = viewModel,
                        navCallbacks = navCallbacks,
                        bookshelfBooks = bookshelfBooks,
                        historyKeys = historyKeys,
                        spanCount = spanCount,
                        styleIsVideo = styleIsVideo,
                        styleCols = styleCols,
                        bookshelfVersion = bookshelfVersion,
                        shelfCoverSlot = resolvedShelfCoverSlot,
                    )
                } else {
                    SearchOptionsRow(
                        options = viewModel.searchOptions,
                        version = searchOptionsVersion,
                        onOptionChanged = { viewModel.search(viewModel.searchKey, resetOptions = false) },
                    )
                    ResultArea(
                        viewModel = viewModel,
                        navCallbacks = navCallbacks,
                        books = resultBooks,
                        isSearching = isSearching,
                        hasMore = hasMore,
                        spanCount = spanCount,
                        styleIsVideo = styleIsVideo,
                        styleCols = styleCols,
                        bookshelfVersion = bookshelfVersion,
                        coverSlot = resolvedCoverSlot,
                    )
                }
            }
            RefreshBar(isSearching, Modifier.align(Alignment.TopStart))
            StartStopFab(
                // 对照 searchFinally: 手动停止后按钮隐藏, 不显示"继续搜索"
                visible = isSearching ||
                    (!manualStopped && hasMore && viewModel.searchKey.isNotEmpty()),
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
        hint = stringResource(Res.string.search_book_key),
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
        // 顺序对照 book_search.xml + onMenuOpened: 静态项在前, 动态范围组居中, 日志 (orderInCategory 9999) 最后
        CheckMenuItem(stringResource(Res.string.precision_search), precisionSearch) {
            dismiss(); viewModel.togglePrecisionSearch()
        }
        TextMenuItem(stringResource(Res.string.book_source_manage)) {
            dismiss(); navCallbacks.onManageBookSources()
        }
        TextMenuItem(stringResource(Res.string.groups_or_source)) {
            dismiss(); navCallbacks.onAlertSearchScope()
        }
        TextMenuItem(stringResource(Res.string.source_filter_rule)) {
            dismiss(); navCallbacks.onShowSourceFilterRule()
        }
        names.forEach { name ->
            CheckMenuItem(name, checked = true) { dismiss(); viewModel.removeScopeName(name) }
        }
        CheckMenuItem(stringResource(Res.string.all_source), checked = names.isEmpty()) {
            dismiss(); viewModel.selectScopeAll()
        }
        TextMenuItem(stringResource(Res.string.log)) { dismiss(); navCallbacks.onShowAppLog() }
    }
}

@Composable
private fun TextMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = AppTheme.colors.primaryText)
    }
}

@Composable
private fun CheckMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    DropdownMenuItem(
        onClick = onClick,
    ) {
        // 固定间距而非 weight: weight 让整行参与测量, 菜单项多时每项都多算一遍
        Text(text, color = colors.primaryText)
        Spacer(Modifier.width(12.dp))
        AppMenuCheckbox(checked = checked)
    }
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
    bookshelfVersion: Int,
    shelfCoverSlot: @Composable (Book, Modifier, isVideoCover: Boolean) -> Unit,
) {
    val colors = AppTheme.colors
    val navPad = rememberNavigationBarPaddingValues()
    if (bookshelfBooks.isNotEmpty()) {
        Text(
            text = stringResource(Res.string.bookshelf),
            color = colors.primaryText,
            fontSize = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        if (styleCols == 0 || (styleCols == 1 && !styleIsVideo)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(bookshelfBooks, key = { it.bookUrl }) { book ->
                    ShelfListItem(
                        book = book,
                        isVideoStyle = styleCols == 0 && styleIsVideo,
                        coverReloadTick = bookshelfVersion,
                        refreshingUrls = emptySet(),
                        showLastUpdateTime = true,
                        showKindIntro = true,
                        onClick = { navCallbacks.onBookClick(book) },
                        onLongClick = { navCallbacks.onBookClick(book, true) },
                        // 对照原版 BookAdapter: kind/intro/更新时间恒显(不受书架配置门控),
                        // flHasNew.gone() 故不画未读徽标
                        forceShowKind = true,
                        forceShowIntro = true,
                        forceShowUpdateTime = true,
                        hideUnread = true,
                        coverSlot = shelfCoverSlot,
                        lastUpdateTextSlot = {
                            ShelfLastUpdateText(
                                book.durChapterTime,
                                remember { mutableIntStateOf(0) },
                            )
                        },
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = rememberResponsiveColumns(spanCount),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(bookshelfBooks, key = { it.bookUrl }) { book ->
                    if (styleIsVideo) {
                        ShelfVideoItem(
                            book = book,
                            coverReloadTick = bookshelfVersion,
                            onClick = { navCallbacks.onBookClick(book) },
                            onLongClick = { navCallbacks.onBookClick(book, true) },
                            coverSlot = shelfCoverSlot,
                        )
                    } else {
                        ShelfGridItem(
                            book = book,
                            coverReloadTick = bookshelfVersion,
                            refreshingUrls = emptySet(),
                            onClick = { navCallbacks.onBookClick(book) },
                            onLongClick = { navCallbacks.onBookClick(book, true) },
                            coverSlot = shelfCoverSlot,
                        )
                    }
                }
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.searchHistory),
                color = colors.primaryText,
                fontSize = 14.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp),
            )
            if (historyKeys.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.clear),
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
                .padding(navPad),
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

// ===== 单源搜索选项 =====

@Composable
private fun SearchOptionsRow(
    options: List<ExploreOption>,
    version: Int,
    onOptionChanged: () -> Unit,
) {
    if (options.isEmpty()) return
    var localVersion by remember { mutableIntStateOf(0) }
    var dialogOptionName by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_EXPRESSION") version
    localVersion
    Column(Modifier.fillMaxWidth()) {
        options.forEach { option ->
            key(option.name) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .then(
                            if (option.multiSelect) {
                                Modifier.clickable { dialogOptionName = option.name }
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (option.multiSelect) {
                        StrokeTextChip(
                            text = option.name,
                            textColor = AppTheme.colors.primaryText,
                        ) { dialogOptionName = option.name }
                        option.options.forEach { (label, value) ->
                            if (value in option.selectedValues) {
                                Spacer(Modifier.width(4.dp))
                                StrokeTextChip(
                                    text = label,
                                    textColor = AppTheme.colors.primaryText,
                                ) { dialogOptionName = option.name }
                            }
                        }
                    } else {
                        StrokeTextChip(
                            text = option.name,
                            textColor = AppTheme.colors.primaryText,
                        ) {
                            if (option.resetToDefault()) {
                                localVersion++
                                onOptionChanged()
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        option.options.forEach { (label, value) ->
                            RadioChip(
                                text = label,
                                checked = option.selectedValue == value,
                            ) {
                                val changed = if (option.selectedValue == value) false else {
                                    option.selectedValue = value
                                    true
                                }
                                if (changed) {
                                    localVersion++
                                    onOptionChanged()
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
            }
        }
    }
    options.firstOrNull { it.multiSelect && it.name == dialogOptionName }?.let { option ->
        MultiSelectOptionDialog(
            option = option,
            onDismiss = { dialogOptionName = null },
            onConfirm = { selectedValues ->
                if (selectedValues != option.selectedValues) {
                    option.selectedValues.clear()
                    option.selectedValues.addAll(selectedValues)
                    localVersion++
                    onOptionChanged()
                }
                dialogOptionName = null
            },
        )
    }
}

@Composable
private fun MultiSelectOptionDialog(
    option: ExploreOption,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var query by remember(option) { mutableStateOf("") }
    var working by remember(option) { mutableStateOf(option.selectedValues.toSet()) }
    val visibleOptions = remember(option, query) {
        val filter = query.trim()
        if (filter.isEmpty()) {
            option.options
        } else {
            option.options.filter { (label, value) ->
                label.contains(filter, ignoreCase = true) ||
                    value.contains(filter, ignoreCase = true)
            }
        }
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = option.name,
        neutralButton = AlertButton(stringResource(Res.string.clear), dismissOnClick = false) {
            working = emptySet()
        },
        cancelButton = AlertButton(stringResource(Res.string.cancel)),
        okButton = AlertButton(
            text = stringResource(Res.string.ok),
            dismissOnClick = false,
            onClick = { onConfirm(working) },
        ),
    ) {
        AppSearchField(
            value = query,
            onValueChange = { query = it },
            hint = stringResource(Res.string.search),
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp),
        )
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp),
        ) {
            items(visibleOptions) { (label, value) ->
                val checked = value in working
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = checked,
                            role = Role.Checkbox,
                        ) { selected ->
                            working = if (selected) working + value else working - value
                        }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppCheckbox(checked = checked, onCheckedChange = null)
                    Text(
                        text = label,
                        color = AppTheme.colors.primaryText,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(start = 8.dp),
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
    navCallbacks: SearchNavCallbacks,
    books: List<SearchBook>,
    isSearching: Boolean,
    hasMore: Boolean,
    spanCount: Int,
    styleIsVideo: Boolean,
    styleCols: Int,
    bookshelfVersion: Int,
    coverSlot: @Composable (SearchBook, Modifier, isVideoCover: Boolean) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") bookshelfVersion // 书架增删时重组刷新绿点
    if (styleCols == 0 || (styleCols == 1 && !styleIsVideo)) {
        val state = rememberLazyListState()
        // 新批次插到头部时回顶 (对齐原 AdapterDataObserver 的 positionStart==0);
        // 仅列表停在顶部时才回顶, 滚动中不打断 (对照书架 ShelfBooksContent 的 firstKey 守卫)
        val firstKey = books.firstOrNull()?.let { "${it.name}|${it.author}" }
        LaunchedEffect(firstKey) {
            if (firstKey != null && state.firstVisibleItemIndex <= 1) state.scrollToItem(0)
        }
        // 触底续搜 (对齐原 canScrollVertically(1)==false -> scrollToBottom)
        LaunchedEffect(state) {
            snapshotFlow { state.layoutInfo.totalItemsCount to state.canScrollForward }
                .distinctUntilChanged()
                .collect { (count, forward) ->
                    if (count > 0 && !forward) viewModel.scrollToBottom()
                }
        }
        FastScrollLazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(
                books,
                key = { "${it.origin}|${it.bookUrl}" },
                contentType = { "searchBook" }) { book ->
                val isVideoStyle = styleCols == 0 && styleIsVideo
                SearchListItem(
                    book = book,
                    isVideoStyle = isVideoStyle,
                    inBookshelf = viewModel.isInBookShelf(book),
                    showShelfDot = true,
                    originCount = book.origins.size,
                    intro = book.intro,
                    onClick = { navCallbacks.onBookClick(book) },
                    onLongClick = { navCallbacks.onBookClick(book, true) },
                    coverSlot = { modifier -> coverSlot(book, modifier, isVideoStyle) },
                )
            }
        }
    } else {
        val state = rememberLazyGridState()
        // 同列表分支: 仅停在顶部附近时回顶, 滚动中不打断
        val firstKey = books.firstOrNull()?.let { "${it.name}|${it.author}" }
        LaunchedEffect(firstKey) {
            if (firstKey != null && state.firstVisibleItemIndex <= 1) state.scrollToItem(0)
        }
        LaunchedEffect(state) {
            snapshotFlow { state.layoutInfo.totalItemsCount to state.canScrollForward }
                .distinctUntilChanged()
                .collect { (count, forward) ->
                    if (count > 0 && !forward) viewModel.scrollToBottom()
                }
        }
        FastScrollLazyVerticalGrid(
            columns = rememberResponsiveColumns(spanCount),
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(
                books,
                key = { "${it.origin}|${it.bookUrl}" },
                contentType = { "searchBook" }) { book ->
                val displayBook = remember(book) { book.toBook() }
                val cover: @Composable (Book, Modifier, Boolean) -> Unit = { _, modifier, isVideoCover ->
                    coverSlot(book, modifier, isVideoCover)
                }
                if (styleIsVideo) {
                    ShelfVideoItem(
                        book = displayBook,
                        coverReloadTick = 0,
                        onClick = { navCallbacks.onBookClick(book) },
                        onLongClick = { navCallbacks.onBookClick(book, true) },
                        coverSlot = cover,
                    )
                } else {
                    ShelfGridItem(
                        book = displayBook,
                        coverReloadTick = 0,
                        refreshingUrls = emptySet(),
                        onClick = { navCallbacks.onBookClick(book) },
                        onLongClick = { navCallbacks.onBookClick(book, true) },
                        coverSlot = cover,
                    )
                }
            }
        }
    }
}

// ===== 条目 (List tier, 对照 item_bookshelf_list + ExploreShowAdapter.bind) =====

/**
 * 搜索结果 List 条目。原版复用 item_bookshelf_list (ExploreShowAdapter),
 * 与书架条目同构, 差别只在: 绿点 ivInBookshelf 显示书架命中、bvOriginCount 显示多源数、
 * tvRead/tvLastUpdateTime 恒隐、kind/intro 不受书架配置开关影响。
 */
@Composable
private fun SearchListItem(
    book: BaseBook,
    isVideoStyle: Boolean,
    inBookshelf: Boolean,
    showShelfDot: Boolean,
    originCount: Int,
    intro: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    coverSlot: @Composable (Modifier) -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
    ) {
        // 视频列表按原 applyCoverHeight 收窄高度，宽度始终由封面比例反算。
        // 换算别每次重组都做, 仅跟随视频样式变化 (对照书架 shelfCoverHeightDp)
        val coverHeight = remember(isVideoStyle) {
            AppConfigProviders.get().bookshelfCoverHeight
                .let { if (isVideoStyle) (it * 0.75f).toInt() else it }
        }
        Box(Modifier.height(coverHeight.dp)) {
            coverSlot(Modifier.fillMaxHeight())
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
                    // 对照 iv_in_bookshelf: 8dp 圆点 @color/md_green_600
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(ShelfHitDotColor, CircleShape),
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
                // 对照 bv_origin_count: 多源数徽标 (BadgeView, count<=0 自动隐藏)
                UnreadBadge(originCount, highlight = false)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShelfRowIcon("ic_author")
                Text(
                    // 对照书架 ShelfListItem: 作者解析只算一次
                    text = remember(book.author) { book.getRealAuthor() },
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val kinds = remember(book.kind, book.wordCount) { book.getKindList() }
            if (kinds.isNotEmpty()) KindLabels(kinds)
            if (!book.latestChapterTitle.isNullOrEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShelfRowIcon("ic_book_last")
                    Text(
                        text = book.latestChapterTitle.toString(),
                        color = colors.secondaryText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 对照 SearchBook.trimIntro: 简介为空时回落"暂无简介", 恒占一行
            // trim 只算一次, 别每次重组都做字符串处理
            val introText = remember(intro) { intro?.trim()?.takeIf { it.isNotEmpty() } }
            Text(
                text = introText ?: stringResource(Res.string.intro_show_null),
                color = colors.secondaryText,
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/** 书架命中绿点 (对照 @color/md_green_600) */
private val ShelfHitDotColor = Color(0xFF43A047)

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
            backgroundColor = Color.Transparent,
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
            contentDescription = stringResource(Res.string.stop),
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
        stringResource(Res.string.search_result_empty_close_precision, scopeDisplay)
    } else {
        stringResource(Res.string.search_result_empty_switch_all, scopeDisplay)
    }
    io.legado.app.ui.compose.component.AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.search_result_empty),
        message = message,
        okButton = io.legado.app.ui.compose.component.AlertButton(
            text = stringResource(Res.string.ok),
            onClick = if (precision) onDisablePrecision else onSwitchAll,
        ),
        cancelButton = io.legado.app.ui.compose.component.AlertButton(
            text = stringResource(Res.string.cancel),
            onClick = null,
        ),
    )
}
