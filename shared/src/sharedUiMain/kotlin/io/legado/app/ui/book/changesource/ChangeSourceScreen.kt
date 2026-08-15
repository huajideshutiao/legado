package io.legado.app.ui.book.changesource

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.format
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.all_source
import legado.shared.generated.resources.book_source_manage
import legado.shared.generated.resources.checkAuthor
import legado.shared.generated.resources.close
import legado.shared.generated.resources.delete_source
import legado.shared.generated.resources.disable_source
import legado.shared.generated.resources.edit_source
import legado.shared.generated.resources.go_to_bottom
import legado.shared.generated.resources.go_to_top
import legado.shared.generated.resources.group
import legado.shared.generated.resources.ic_arrow_back
import legado.shared.generated.resources.ic_arrow_down
import legado.shared.generated.resources.ic_check
import legado.shared.generated.resources.ic_praise
import legado.shared.generated.resources.like_source
import legado.shared.generated.resources.load_info
import legado.shared.generated.resources.load_toc
import legado.shared.generated.resources.load_word_count
import legado.shared.generated.resources.not_like_source
import legado.shared.generated.resources.outline_filter_alt_24
import legado.shared.generated.resources.refresh_list
import legado.shared.generated.resources.respondTime
import legado.shared.generated.resources.screen
import legado.shared.generated.resources.success
import legado.shared.generated.resources.to_bottom
import legado.shared.generated.resources.to_top
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 换源标题栏：复刻 dialog_title_bar + change_source 菜单
 * (筛选 SearchView 展开态 / 启停 / 溢出菜单)。
 *
 * 下沉改动:
 * - painterResource(R.drawable.X) → rememberPainter("X")
 * - stringResource(R.string.X) → stringResource(Res.string.X)
 * - colorResource(R.color.X) → rememberColor("X")
 * - 各 internal Composable 改为 public, 供 app 端 ChangeBookSourceDialog /
 *   ChangeChapterSourceDialog 跨模块调用
 */
@Composable
fun ChangeSourceTitleBar(
    title: String,
    subtitle: String?,
    searchMode: Boolean,
    screenKey: String,
    searching: Boolean,
    onBack: () -> Unit,
    onSearchModeChange: (Boolean) -> Unit,
    onScreen: (String) -> Unit,
    onStartStop: () -> Unit,
    menuContent: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val colors = AppTheme.colors
    val focusRequester = remember { FocusRequester() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bottomBackground)
            .height(DesignTokens.viewHeightXl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {
            if (searchMode) {
                onScreen("")
                onSearchModeChange(false)
            } else {
                onBack()
            }
        }) {
            Icon(
                painter = painterResource(Res.drawable.ic_arrow_back),
                contentDescription = null,
                tint = colors.primaryText,
            )
        }
        if (searchMode) {
            AppSearchField(
                value = screenKey,
                onValueChange = onScreen,
                hint = stringResource(Res.string.screen),
                modifier = Modifier.weight(1f),
                textFieldModifier = Modifier.focusRequester(focusRequester),
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colors.primaryText,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { onSearchModeChange(true) }) {
                Icon(
                    painter = painterResource(Res.drawable.outline_filter_alt_24),
                    contentDescription = stringResource(Res.string.screen),
                    tint = colors.primaryText,
                )
            }
        }
        IconButton(onClick = onStartStop) {
            Icon(
                painter = rememberPainter(
                    if (searching) "ic_stop_black_24dp" else "ic_refresh_black_24dp"
                ),
                contentDescription = rememberString(if (searching) "stop" else "refresh"),
                tint = colors.primaryText,
            )
        }
        OverflowMenu { dismiss -> menuContent(dismiss) }
    }
}

@Composable
fun TextMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = AppTheme.colors.primaryText)
    }
}

/** 对照 View 菜单 checkable 项：右侧勾选框 */
@Composable
fun CheckMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = AppTheme.colors.primaryText)
        Spacer(Modifier.weight(1f))
        AppMenuCheckbox(checked = checked)
    }
}

/** 分组二级菜单单选项 (换源菜单「分组」subMenu 行为, 同包 ChangeChapterSourceScreen 共用) */
@Composable
fun RadioMenuItem(text: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
    ) {
        AppRadioButton(selected = selected, onClick = null)
        Text(text, color = AppTheme.colors.primaryText)
    }
}

/** 对照 RefreshProgressBar：标题栏下 2dp 搜索中指示，E-Ink 用静态色条 */
@Composable
fun ChangeSourceRefreshBar(visible: Boolean) {
    if (!visible) return
    val colors = AppTheme.colors
    if (LocalEInk.current) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.accent),
        )
    } else {
        LinearProgressIndicator(
            color = colors.accent,
            backgroundColor = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
        )
    }
}

/** 底栏：当前源/进度文字 + 回顶 + 到底，对照 ll_bottom_bar */
@Composable
fun ChangeSourceBottomBar(
    durText: String,
    onDurClick: () -> Unit,
    onTop: () -> Unit,
    onBottom: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onDurClick)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = durText,
                color = colors.primaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        BottomBarIcon("ic_arrow_drop_up", stringResource(Res.string.go_to_top), onTop)
        BottomBarIcon("ic_arrow_drop_down", stringResource(Res.string.go_to_bottom), onBottom)
    }
}

@Composable
private fun BottomBarIcon(iconKey: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .width(36.dp)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = description,
            tint = AppTheme.colors.primaryText,
        )
    }
}

/**
 * 搜索结果条目，对照 item_change_source：
 * 左侧点赞/点踩列、源名+作者+最新章节+字数+响应时间、右侧当前源勾选；长按弹源操作菜单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchBookItem(
    book: SearchBook,
    isCurSource: Boolean,
    loadWordCount: Boolean,
    getScore: () -> Int,
    setScore: (Int) -> Unit,
    onClick: () -> Unit,
    onTop: () -> Unit,
    onBottom: () -> Unit,
    onEdit: () -> Unit,
    onDisable: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    var score by remember(book.bookUrl) { mutableIntStateOf(getScore()) }
    var menuExpanded by remember { mutableStateOf(false) }
    // 预取格式化串: rememberString 是 @Composable, 不能在 lambda 里调
    val strRespondTime = stringResource(Res.string.respondTime)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (score >= 0) {
                    ScoreIcon(
                        tintColor = rememberColor(if (score > 0) "md_red_A200" else "md_red_100"),
                        description = stringResource(Res.string.like_source),
                        flip = false,
                    ) {
                        val new = if (score > 0) 0 else 1
                        score = new
                        setScore(new)
                    }
                }
                if (score <= 0) {
                    ScoreIcon(
                        tintColor = rememberColor(if (score < 0) "md_blue_A200" else "md_blue_100"),
                        description = stringResource(Res.string.not_like_source),
                        flip = true,
                    ) {
                        val new = if (score < 0) 0 else -1
                        score = new
                        setScore(new)
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.originName,
                        color = colors.primaryText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = book.getRealAuthor(),
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                }
                Text(
                    text = book.getDisplayLastChapterTitle(),
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (loadWordCount && !book.chapterWordCountText.isNullOrBlank()) {
                    Text(
                        text = book.chapterWordCountText!!,
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                    )
                }
                if (loadWordCount && book.respondTime >= 0) {
                    Text(
                        text = strRespondTime.format(book.respondTime),
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                    )
                }
            }
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (isCurSource) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_check),
                        contentDescription = null,
                        tint = colors.primaryText,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        // 长按菜单，对照 change_source_item PopupMenu
        AppDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            TextMenuItem(stringResource(Res.string.to_top)) { menuExpanded = false; onTop() }
            TextMenuItem(stringResource(Res.string.to_bottom)) { menuExpanded = false; onBottom() }
            TextMenuItem(stringResource(Res.string.edit_source)) { menuExpanded = false; onEdit() }
            TextMenuItem(stringResource(Res.string.disable_source)) {
                menuExpanded = false; onDisable()
            }
            TextMenuItem(stringResource(Res.string.delete_source)) {
                menuExpanded = false; onDelete()
            }
        }
    }
}

@Composable
private fun ScoreIcon(tintColor: Color, description: String, flip: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_praise),
            contentDescription = description,
            tint = tintColor,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { if (flip) rotationX = 180f },
        )
    }
}

/**
 * 章节换源 toc 预览覆盖层，对照 cl_toc：收起条 + 章节列表 + 加载圈。
 */
@Composable
fun ChapterTocPanel(
    toc: List<BookChapter>?,
    durChapterIndex: Int,
    loading: Boolean,
    onHide: () -> Unit,
    onClickChapter: (BookChapter, String?) -> Unit,
) {
    val colors = AppTheme.colors
    val listState = rememberLazyListState()
    // 目录载入后定位到当前章节上方 5 条，对照 scrollToPosition(durChapterIndex - 5)
    LaunchedEffect(toc) {
        if (!toc.isNullOrEmpty()) {
            listState.scrollToItem((durChapterIndex - 5).coerceAtLeast(0))
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // 空点击仅拦截触摸穿透(原 cl_toc 面板 clickable), 无按压反馈是有意的
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clickable(onClick = onHide),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_arrow_down),
                contentDescription = stringResource(Res.string.close),
                tint = colors.primaryText,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (toc != null) {
                FastScrollLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(toc, key = { index, _ -> index }) { index, chapter ->
                        TocItemRow(chapter, chapter.index == durChapterIndex) {
                            onClickChapter(chapter, toc.getOrNull(index + 1)?.url)
                        }
                    }
                }
            }
            if (loading) {
                CircularProgressIndicator(
                    color = colors.accent,
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center),
                )
            }
        }
    }
}

/** 目录条目，对照 item_chapter_list：卷名 btn_bg 底、当前章节 accent + 勾选 */
@Composable
private fun TocItemRow(chapter: BookChapter, isDur: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val volumeBg = if (chapter.isVolume) {
        Modifier.background(rememberColor("btn_bg"))
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(volumeBg)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                color = if (isDur) colors.accent else colors.primaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!chapter.tag.isNullOrEmpty() && !chapter.isVolume) {
                Text(
                    text = chapter.tag!!,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (isDur) {
                Icon(
                    painter = painterResource(Res.drawable.ic_check),
                    contentDescription = stringResource(Res.string.success),
                    tint = colors.secondaryText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 整书换源 UI 交互回调。
 *
 * 实际业务 (搜索/筛选/启停/切源) 留在 [ChangeBookSourceViewModelShared], Route 层
 * 将 navigator 与平台 ViewModel 桥接到本接口。
 */
interface ChangeSourceUiActions {
    /** 返回 (导航器 pop) */
    fun onBack()

    /** 启停搜索 (标题栏刷新按钮) */
    fun onStartStop()

    /** 筛选关键词变更 (searchMode 输入) */
    fun onScreen(key: String)

    /** 切换筛选模式 (放大镜按钮) */
    fun onSearchModeChange(enabled: Boolean)

    /** 点击源条目 (触发切源流程) */
    fun onItemClick(book: SearchBook)
}

/**
 * 整书换源页菜单/检查项回调 (对照 app 端 Dialog.Content 溢出菜单 8 项)。
 *
 * Route 层桥接到 platform 开关字段 + [ChangeBookSourceViewModelShared] 刷新方法。
 */
interface ChangeSourceMenuActions {
    /** 书源管理 (navigator.push(BookSourceManage)) */
    fun onBookSourceManage()

    /** 刷新列表 (viewModel.startRefreshList()) */
    fun onRefreshList()

    /** 校验作者开关 (platform.changeSourceCheckAuthor = value; viewModel.refresh()) */
    fun onCheckAuthorChange(value: Boolean)

    /** 加载字数开关 (platform.changeSourceLoadWordCount = value; viewModel.onLoadWordCountChecked(value)) */
    fun onLoadWordCountChange(value: Boolean)

    /** 加载详情开关 (platform.changeSourceLoadInfo = value) */
    fun onLoadInfoChange(value: Boolean)

    /** 加载目录开关 (platform.changeSourceLoadToc = value) */
    fun onLoadTocChange(value: Boolean)

    /** 关闭 (navigator.pop()) */
    fun onClose()
}

/**
 * 整书换源列表项操作回调 (对照 app 端 Dialog.Content SearchBookItem 调用第 228-242 行)。
 *
 * Route 层桥接到 [ChangeBookSourceViewModelShared] 评分/置顶置底/编辑/禁用/删除方法。
 */
interface ChangeSourceItemActions {
    /** 取评分 (viewModel.getBookScore(book)) */
    fun getScore(book: SearchBook): Int

    /** 设置评分 (viewModel.setBookScore(book, score)) */
    fun setScore(book: SearchBook, score: Int)

    /** 置顶 (viewModel.topSource(book)) */
    fun onTop(book: SearchBook)

    /** 置底 (viewModel.bottomSource(book)) */
    fun onBottom(book: SearchBook)

    /** 编辑书源 (navigator.push(BookSourceEdit(book.origin))) */
    fun onEdit(book: SearchBook)

    /** 禁用书源 (viewModel.disableSource(book)) */
    fun onDisable(book: SearchBook)

    /** 删除书源 (弹确认 alert -> viewModel.del(book) -> 可能 autoChangeSource) */
    fun onDelete(book: SearchBook)
}

/**
 * 整书换源页 (对照 app 端 [ChangeBookSourceDialog])。
 *
 * 下沉自 Dialog 形态: 标题栏 (复用 [ChangeSourceTitleBar]) + 搜索进度条
 * (复用 [ChangeSourceRefreshBar]) + 源列表 ([SearchBookItem] 完整版含点赞/长按菜单)
 * + 底栏 (复用 [ChangeSourceBottomBar]) + 分组二级菜单 (菜单「分组」subMenu 展开,
 *   对照原版 menu_group/upGroupMenu)。
 *
 * 与 [ChangeChapterSourceScreen] 同构, 差异: 列表项用完整 [SearchBookItem] (含点赞/长按菜单/字数列)。
 * 菜单 8 项与列表项操作通过 [ChangeSourceMenuActions] / [ChangeSourceItemActions] 注入。
 *
 * @param state UI 状态 (由 [ChangeSourceScreenModel] 持有)
 * @param book 当前书籍 (标题栏显示书名/作者)
 * @param actions 交互回调 (由 Route 层桥接到 navigator + 平台 ViewModel)
 * @param menuActions 菜单项回调 (由 Route 层桥接到 platform 开关 + ViewModel)
 * @param itemActions 列表项操作回调 (由 Route 层桥接到 ViewModel)
 * @param searchGroup 当前选中分组 (菜单「分组」项标题显示)
 * @param onSearchGroupChange 分组变化回调 (保留供 Route 同步, Screen 内用 state 显示)
 * @param onGroupPickerSelect 分组选中回调 (二级分组菜单选中, Route 层做 stopSearch/refresh/startSearch)
 * @param groups 启用分组列表 (二级分组菜单渲染)
 */
@Composable
fun ChangeSourceScreen(
    state: ChangeSourceUiState,
    book: Book,
    actions: ChangeSourceUiActions,
    menuActions: ChangeSourceMenuActions,
    itemActions: ChangeSourceItemActions,
    searchGroup: String,
    onSearchGroupChange: (String) -> Unit,
    onGroupPickerSelect: (String) -> Unit,
    groups: List<String>,
) {
    // 筛选模式 / 关键词为本地 UI 状态, 与 app 端 Dialog 同样用 rememberSaveable 持久化
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var screenKey by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        ChangeSourceTitleBar(
            title = book.name,
            subtitle = book.author,
            searchMode = searchMode,
            screenKey = screenKey,
            searching = state.isLoading,
            onBack = actions::onBack,
            onSearchModeChange = {
                searchMode = it
                actions.onSearchModeChange(it)
            },
            onScreen = {
                screenKey = it
                actions.onScreen(it)
            },
            onStartStop = actions::onStartStop,
        ) { dismiss ->
            // 对照 app 端 Dialog.Content 第 179-220 行 8 个菜单项;
            // 「分组」项点击后用分组列表替换一级菜单 (对照原 Android menu_search_group 的
            // submenu 展开行为, 不再弹独立分组对话框, 2026-08-06)。
            var showGroup by remember { mutableStateOf(false) }
            if (showGroup) {
                val hasSelected = searchGroup.isNotEmpty() && groups.contains(searchGroup)
                RadioMenuItem(stringResource(Res.string.all_source), !hasSelected) {
                    dismiss(); onGroupPickerSelect("")
                }
                groups.forEach { group ->
                    RadioMenuItem(group, group == searchGroup) {
                        dismiss(); onGroupPickerSelect(group)
                    }
                }
            } else {
                TextMenuItem(stringResource(Res.string.book_source_manage)) {
                    dismiss(); menuActions.onBookSourceManage()
                }
                TextMenuItem(stringResource(Res.string.refresh_list)) {
                    dismiss(); menuActions.onRefreshList()
                }
                CheckMenuItem(stringResource(Res.string.checkAuthor), state.checkAuthor) {
                    dismiss()
                    menuActions.onCheckAuthorChange(!state.checkAuthor)
                }
                CheckMenuItem(stringResource(Res.string.load_word_count), state.loadWordCount) {
                    dismiss()
                    menuActions.onLoadWordCountChange(!state.loadWordCount)
                }
                CheckMenuItem(stringResource(Res.string.load_info), state.loadInfo) {
                    dismiss()
                    menuActions.onLoadInfoChange(!state.loadInfo)
                }
                CheckMenuItem(stringResource(Res.string.load_toc), state.loadToc) {
                    dismiss()
                    menuActions.onLoadTocChange(!state.loadToc)
                }
                TextMenuItem(
                    if (searchGroup.isEmpty()) {
                        stringResource(Res.string.group)
                    } else {
                        stringResource(Res.string.group) + "($searchGroup)"
                    }
                ) { showGroup = true }
                TextMenuItem(stringResource(Res.string.close)) {
                    dismiss(); menuActions.onClose()
                }
            }
        }
        ChangeSourceRefreshBar(state.isLoading)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            items(state.sources, key = { it.bookUrl }) { searchBook ->
                // 对照 app 端 Dialog.Content 第 228-242 行 SearchBookItem 完整回调
                SearchBookItem(
                    book = searchBook,
                    isCurSource = searchBook.bookUrl == state.curBookUrl,
                    loadWordCount = state.loadWordCount,
                    getScore = { itemActions.getScore(searchBook) },
                    setScore = { itemActions.setScore(searchBook, it) },
                    onClick = { actions.onItemClick(searchBook) },
                    onTop = { itemActions.onTop(searchBook) },
                    onBottom = { itemActions.onBottom(searchBook) },
                    onEdit = { itemActions.onEdit(searchBook) },
                    onDisable = { itemActions.onDisable(searchBook) },
                    onDelete = { itemActions.onDelete(searchBook) },
                )
            }
        }
        ChangeSourceBottomBar(
            durText = state.durText,
            onDurClick = {
                val index = state.sources.indexOfFirst { it.bookUrl == state.curBookUrl }
                if (index >= 0) scope.launch { listState.scrollToItem(index) }
            },
            onTop = { scope.launch { listState.scrollToItem(0) } },
            onBottom = {
                scope.launch {
                    if (state.sources.isNotEmpty()) listState.scrollToItem(state.sources.lastIndex)
                }
            },
        )
    }
}
