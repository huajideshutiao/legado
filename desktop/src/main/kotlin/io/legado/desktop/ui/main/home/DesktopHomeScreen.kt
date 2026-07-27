package io.legado.desktop.ui.main.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.HomeTab
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.main.home.HomeScreen
import io.legado.app.ui.main.home.HomeUiState
import io.legado.app.ui.main.home.HomeViewModelShared
import io.legado.app.ui.main.home.homeSectionKey
import io.legado.desktop.ui.component.DesktopBookCover

private const val RANK_LIMIT = 5

/**
 * 桌面端主页 Screen 入口 (包装 shared/sharedUiMain 的 [HomeScreen])。
 *
 * 三个 slot 替换为真实 Compose 实现 (对照 app 端 HomeSectionComposables, 封面用
 * [DesktopBookCover.InfoCover] 替代 L3 ShelfCover):
 * - SectionBlock: 标题行 + 按样式渲染 (封面行/排行榜/四行) + 空/加载/错误占位
 * - InfiniteHeader: 标题行 (无限流头部, 占整行随网格滚动)
 * - InfiniteGridCard: 封面 (DesktopBookCover) + 书名, 可点击/长按跳详情
 *
 * openManageSection / openManageTab 接入桌面端管理对话框 ([DesktopHomeTabManageDialog] /
 * [DesktopHomeSectionManageDialog]), 持久化走 [HomeTabHelpShared], 变更后刷新 VM
 * (对照 app 端 EventBus HOME_TAB/HOME_SECTION → HomeTabState 桥接)。
 *
 * @param onBookClick 书籍点击回调 (由 DesktopApp 注入, 跳 BOOK_INFO 详情路由)
 * @param onBookLongClick 书籍长按回调 (同上, app 端长按恒跳详情)
 */
@Composable
fun DesktopHomeScreen(
    onBookClick: (SearchBook) -> Unit = {},
    onBookLongClick: (SearchBook) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { HomeViewModelShared(scope) }
    val state = remember(viewModel) { DesktopHomeState(viewModel) }

    // 订阅 VM 的 5 个 StateFlow, 桥接到 state 的 Compose state (对照 app 端 LiveData observe)
    // 过滤 null 避免初始假触发 (StateFlow 是 hot flow, 立即收到当前值, shared 的可空 StateFlow 初始都是 null)
    LaunchedEffect(viewModel) {
        viewModel.tabsFlow.collect { it?.let(state::onTabs) }
    }
    LaunchedEffect(viewModel) {
        viewModel.sectionsFlow.collect { it?.let(state::onSectionsChanged) }
    }
    LaunchedEffect(viewModel) {
        viewModel.sectionUpdatedFlow.collect { it?.let { (t, s) -> state.onSectionUpdated(t, s) } }
    }
    LaunchedEffect(viewModel) {
        viewModel.sectionLoadingChangedFlow.collect { it?.let { (t, s) -> state.onSectionLoadingChanged(t, s) } }
    }
    LaunchedEffect(viewModel) {
        viewModel.sectionErrorChangedFlow.collect { it?.let { (t, s) -> state.onSectionError(t, s) } }
    }

    // 初始化: 拉取 tabs 列表 (对照 app 端 HomeEffects LaunchedEffect(vm.initTabs))
    LaunchedEffect(viewModel) {
        viewModel.initTabs()
    }

    HomeScreen(
        state = HomeUiState(
            tabs = state.tabs,
            currentPage = state.currentPage,
            tabSections = state.tabSections,
            sectionBooks = state.booksState,
            sectionLoading = state.loadingState,
            sectionError = state.errorState,
            infiniteHasMore = state.hasMoreState,
        ),
        actions = state,
        sectionBlockSlot = { tabTitle, section ->
            DesktopSectionBlock(state, tabTitle, section, onBookClick, onBookLongClick)
        },
        infiniteHeaderSlot = { _, section ->
            DesktopInfiniteHeader(section)
        },
        infiniteGridCardSlot = { _, section, book ->
            DesktopInfiniteGridCard(section, book, onBookClick, onBookLongClick)
        },
    )

    // ---- 管理对话框渲染 (由 state.openManageTab/openManageSection 触发显隐) ----
    if (state.showManageTabDialog) {
        DesktopHomeTabManageDialog(
            tabs = HomeTabHelpShared.getTabs(),
            onAddTab = state::addTab,
            onRenameTab = state::renameTab,
            onDeleteTab = state::deleteTab,
            onDismiss = state::dismissManageTabDialog,
        )
    }
    if (state.showManageSectionDialog) {
        val tabTitle = state.currentTabTitle
        if (tabTitle != null) {
            DesktopHomeSectionManageDialog(
                tabTitle = tabTitle,
                sections = HomeTabHelpShared.getSections(tabTitle),
                onDeleteSection = { section -> state.deleteSection(tabTitle, section) },
                onDismiss = state::dismissManageSectionDialog,
            )
        } else {
            // 无当前 tab (tabs 为空), 直接关闭
            state.dismissManageSectionDialog()
        }
    }
}

// ---- 真实 slot 实现 (对照 app 端 HomeSectionComposables, 封面用 DesktopBookCover) ----

/**
 * 非无限流展示项区块 (对照 app 端 SectionBlock): 标题行 + 按样式渲染内容 + 空/加载/错误占位。
 * 参数 chip 行 (app 端 SectionOptions, L3 AndroidView) 桌面端暂未接入, 展示项按默认参数加载。
 */
@Composable
private fun DesktopSectionBlock(
    state: DesktopHomeState,
    tabTitle: String,
    section: HomeSection,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        DesktopSectionTitleRow(section.title)
        val k = homeSectionKey(tabTitle, section.id)
        val books = state.booksState[k] ?: emptyList()
        when {
            books.isNotEmpty() -> when (section.style) {
                HomeSection.STYLE_RANK_LIST -> DesktopRankColumn(
                    books.take(RANK_LIMIT), onBookClick, onBookLongClick,
                )
                HomeSection.STYLE_FOUR_ROW -> DesktopFourRow(
                    books, onBookClick, onBookLongClick,
                )
                else -> DesktopCoverRow(
                    books, onBookClick, onBookLongClick,
                )
            }
            else -> DesktopSectionPlaceholder(
                loading = state.loadingState[k] ?: false,
                error = state.errorState[k] ?: false,
            )
        }
    }
}

/** 无限流头部 (对照 app 端 InfiniteHeader): 标题行, 占整行随网格滚动。 */
@Composable
private fun DesktopInfiniteHeader(section: HomeSection) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        DesktopSectionTitleRow(section.title)
    }
}

/** 无限流网格单元 (对照 app 端 InfiniteGridCard): 封面 + 书名, 可点击/长按。 */
@Composable
private fun DesktopInfiniteGridCard(
    section: HomeSection,
    book: SearchBook,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .combinedClickable(
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
            ),
    ) {
        DesktopBookCover.InfoCover(
            book = book.toBook(),
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        Text(
            text = book.name,
            color = colors.primaryText,
            fontSize = 12.sp,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 展示项标题行 (对照 app 端 SectionTitleRow): 标题 + 更多 + 右箭头。 */
@Composable
private fun DesktopSectionTitleRow(title: String) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = colors.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // "更多" + 右箭头 (视觉与 app 端一致; 跳发现详情需异步查书源, 桌面端暂不接入点击)
        Text(
            text = rememberString("more"),
            color = colors.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Icon(
            painter = rememberPainter("ic_arrow_right"),
            contentDescription = rememberString("more"),
            tint = colors.secondaryText,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 空/加载/错误占位 (对照 app 端 SectionPlaceholder)。 */
@Composable
private fun DesktopSectionPlaceholder(loading: Boolean, error: Boolean) {
    val colors = AppTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 96.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp),
            )
            error -> Text(
                text = rememberString("source_invalid"),
                color = colors.secondaryText,
                fontSize = 12.sp,
            )
            else -> Text(
                text = rememberString("empty"),
                color = colors.secondaryText,
                fontSize = 12.sp,
            )
        }
    }
}

/** 横向封面行 STYLE_COVER_ROW (对照 app 端 CoverRow): 小说竖封面卡 LazyRow。 */
@Composable
private fun DesktopCoverRow(
    books: List<SearchBook>,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        items(books, key = { it.bookUrl }) { book ->
            DesktopNovelCoverCard(book, onBookClick, onBookLongClick)
        }
    }
}

/** 小说竖封面卡 (对照 app 端 NovelCoverCard): 封面 120x160 + 书名(2行) + 作者。 */
@Composable
private fun DesktopNovelCoverCard(
    book: SearchBook,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .width(120.dp)
            .padding(4.dp)
            .combinedClickable(
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
            ),
    ) {
        DesktopBookCover.InfoCover(
            book = book.toBook(),
            modifier = Modifier.width(120.dp).height(160.dp),
        )
        Text(
            text = book.name,
            color = colors.primaryText,
            fontSize = 12.sp,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = book.author,
            color = colors.secondaryText,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 排行榜 STYLE_RANK_LIST (对照 app 端 RankColumn): 竖排前 N 名, 带名次色标。 */
@Composable
private fun DesktopRankColumn(
    books: List<SearchBook>,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        books.forEachIndexed { index, book ->
            DesktopRankItem(
                rank = index + 1,
                book = book,
                showRank = true,
                onBookClick = onBookClick,
                onBookLongClick = onBookLongClick,
            )
        }
    }
}

/** 四行 STYLE_FOUR_ROW (对照 app 端 FourRow): 横向翻页, 每列 4 本(不显名次)。 */
@Composable
private fun DesktopFourRow(
    books: List<SearchBook>,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
) {
    val rowState = rememberLazyListState()
    val columns = remember(books) { books.chunked(4) }
    LazyRow(
        state = rowState,
        flingBehavior = rememberSnapFlingBehavior(rowState, SnapPosition.Start),
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        items(columns, key = { it.firstOrNull()?.bookUrl ?: it.hashCode() }) { column ->
            Column(Modifier.width(220.dp)) {
                column.forEach { book ->
                    DesktopRankItem(
                        rank = 0,
                        book = book,
                        showRank = false,
                        onBookClick = onBookClick,
                        onBookLongClick = onBookLongClick,
                    )
                }
            }
        }
    }
}

/** 排行/四行的行项 (对照 app 端 RankItem): 名次 + 小封面(70dp) + 名/作者。 */
@Composable
private fun DesktopRankItem(
    rank: Int,
    book: SearchBook,
    showRank: Boolean,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showRank) {
            // 前 3 名红/橙/黄色标 (对照 app 端 R.color.md_red_600/md_orange_700/md_yellow_700)
            val rankColor = when (rank) {
                1 -> Color(0xFFE53935)
                2 -> Color(0xFFF57C00)
                3 -> Color(0xFFFBC02D)
                else -> colors.secondaryText
            }
            Text(
                text = rank.toString(),
                color = rankColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(28.dp),
                textAlign = TextAlign.Center,
            )
        }
        DesktopBookCover.InfoCover(
            book = book.toBook(),
            modifier = Modifier
                .padding(start = if (showRank) 8.dp else 0.dp)
                .height(70.dp)
                .width(52.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = book.name,
                color = colors.primaryText,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.author,
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// ---- 管理对话框 (对照 app 端 HomeTabManageDialog / HomeSectionManageDialog) ----
// app 端用 BaseComposeDialogFragment + RuleManageScaffold (拖拽排序, L3), 桌面端用纯
// @Composable Dialog + LazyColumn (复用 shared DialogTitleBar/AppTextButton/AppAlertDialog,
// Arco Design 规范); 不接入拖拽排序 (与 shared GroupManageDialog 一致)。

/**
 * 管理分组对话框 (对照 app 端 HomeTabManageDialog)。
 * 列表 + 新建(内嵌输入) + 重命名(内嵌输入) + 删除(二次确认), 持久化走 [HomeTabHelpShared]。
 * 对照 shared GroupManageDialog 结构 (同 Arco Design 规范, 但数据实体为 HomeTab)。
 */
@Composable
private fun DesktopHomeTabManageDialog(
    tabs: List<HomeTab>,
    onAddTab: (String) -> Unit,
    onRenameTab: (String, String) -> Unit,
    onDeleteTab: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    // 编辑态: editingTab 非 null = 编辑该 tab; addingTab = true = 新建
    var editingTab by remember { mutableStateOf<HomeTab?>(null) }
    var addingTab by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf("") }
    // 删除确认态: 待删除的分组 (非空时弹二次确认 alert)
    var deletingTab by remember { mutableStateOf<HomeTab?>(null) }
    val inEditMode = editingTab != null || addingTab

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = colors.fillet,
        ) {
            Column {
                DialogTitleBar(
                    title = rememberString("home_tab_manage"),
                    onBack = onDismiss,
                    actions = {
                        IconButton(onClick = {
                            addingTab = true
                            editingTab = null
                            editingName = ""
                        }) {
                            Icon(
                                painter = rememberPainter("ic_add"),
                                contentDescription = rememberString("add"),
                                tint = colors.primaryText,
                            )
                        }
                    },
                )
                if (inEditMode) {
                    Column(Modifier.padding(16.dp)) {
                        AppOutlinedTextField(
                            value = editingName,
                            onValueChange = { editingName = it },
                            label = rememberString("group_name"),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            AppTextButton(text = rememberString("cancel")) {
                                addingTab = false
                                editingTab = null
                                editingName = ""
                            }
                            Spacer(Modifier.width(8.dp))
                            AppTextButton(
                                text = rememberString("ok"),
                                enabled = editingName.isNotBlank(),
                            ) {
                                val name = editingName.trim()
                                val editing = editingTab
                                if (addingTab) {
                                    onAddTab(name)
                                } else if (editing != null) {
                                    onRenameTab(editing.title, name)
                                }
                                addingTab = false
                                editingTab = null
                                editingName = ""
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(items = tabs, key = { it.title }) { tab ->
                            TabManageItem(
                                tab = tab,
                                onRename = {
                                    editingTab = tab
                                    addingTab = false
                                    editingName = tab.title
                                },
                                onDelete = { deletingTab = tab },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        AppTextButton(text = rememberString("ok"), onClick = onDismiss)
                    }
                }
            }
        }
    }

    // 删除二次确认 (对照 app 端 GroupEditDialog.alert(R.string.delete, R.string.sure_del))
    deletingTab?.let { tab ->
        AppAlertDialog(
            onDismissRequest = { deletingTab = null },
            title = rememberString("delete"),
            message = rememberString("sure_del"),
            okButton = AlertButton(
                text = rememberString("ok"),
                onClick = {
                    onDeleteTab(tab.title)
                    deletingTab = null
                },
            ),
            cancelButton = AlertButton(text = rememberString("cancel")),
        )
    }
}

/** 分组管理列表项 (对照 app 端 HomeTabManageDialog.TabItem): 分组名 + 组件数 + 编辑/删除。 */
@Composable
private fun TabManageItem(
    tab: HomeTab,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = tab.title,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rememberString("widget_count", tab.sections.size),
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = rememberString("edit"),
            color = colors.secondaryText,
            fontSize = 14.sp,
            modifier = Modifier.clickable(onClick = onRename).padding(8.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = rememberString("delete"),
            color = colors.secondaryText,
            fontSize = 14.sp,
            modifier = Modifier.clickable(onClick = onDelete).padding(8.dp),
        )
    }
}

/**
 * 管理展示项对话框 (对照 app 端 HomeSectionManageDialog)。
 * 列表 + 删除(二次确认), 持久化走 [HomeTabHelpShared]。
 * 新建/编辑展示项需选书源+exploreUrl (app 端 HomeSectionEditDialog, L3 复杂表单), 桌面端暂未接入。
 */
@Composable
private fun DesktopHomeSectionManageDialog(
    tabTitle: String,
    sections: List<HomeSection>,
    onDeleteSection: (HomeSection) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    var deletingSection by remember { mutableStateOf<HomeSection?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = colors.fillet,
        ) {
            Column {
                DialogTitleBar(
                    title = rememberString("home_manage") + "：" + tabTitle,
                    onBack = onDismiss,
                )
                if (sections.isEmpty()) {
                    Text(
                        text = rememberString("no_widget"),
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(items = sections, key = { it.id }) { section ->
                            SectionManageItem(
                                section = section,
                                onDelete = { deletingSection = section },
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AppTextButton(text = rememberString("ok"), onClick = onDismiss)
                }
            }
        }
    }

    deletingSection?.let { section ->
        AppAlertDialog(
            onDismissRequest = { deletingSection = null },
            title = rememberString("delete"),
            message = rememberString("sure_del"),
            okButton = AlertButton(
                text = rememberString("ok"),
                onClick = {
                    onDeleteSection(section)
                    deletingSection = null
                },
            ),
            cancelButton = AlertButton(text = rememberString("cancel")),
        )
    }
}

/** 展示项管理列表项 (对照 app 端 HomeSectionManageDialog.SectionItem): 标题 + 样式·源名 + 删除。 */
@Composable
private fun SectionManageItem(
    section: HomeSection,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = section.title,
                color = colors.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${styleName(section.style)} · ${section.sourceName}",
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = rememberString("delete"),
            color = colors.secondaryText,
            fontSize = 14.sp,
            modifier = Modifier.clickable(onClick = onDelete).padding(8.dp),
        )
    }
}

/** 展示项样式名 (对照 app 端 home_style_* string, jvm 资源表未注册, 硬编码中文)。 */
private fun styleName(style: Int): String = when (style) {
    HomeSection.STYLE_RANK_LIST -> jvmGetString("home_style_rank_list")
    HomeSection.STYLE_FOUR_ROW -> jvmGetString("home_style_four_row")
    HomeSection.STYLE_INFINITE_GRID -> jvmGetString("home_style_infinite_grid")
    else -> jvmGetString("home_style_cover_row")
}
