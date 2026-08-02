package io.legado.app.ui.book.toc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.StringUtils
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookmark
import legado.shared.generated.resources.chapter_list
import legado.shared.generated.resources.export
import legado.shared.generated.resources.export_md
import legado.shared.generated.resources.go_to_bottom
import legado.shared.generated.resources.go_to_top
import legado.shared.generated.resources.ic_arrow_drop_down
import legado.shared.generated.resources.ic_arrow_drop_up
import legado.shared.generated.resources.ic_check
import legado.shared.generated.resources.ic_lock_outline
import legado.shared.generated.resources.ic_outline_cloud_24
import legado.shared.generated.resources.ic_sort
import legado.shared.generated.resources.load_word_count
import legado.shared.generated.resources.log
import legado.shared.generated.resources.reverse_toc
import legado.shared.generated.resources.search
import legado.shared.generated.resources.split_long_chapter
import legado.shared.generated.resources.txt_toc_rule
import legado.shared.generated.resources.use_replace
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

// ===== state / actions =====

/** 一次性列表定位命令，tick 去重（对照原 TocActivity.ScrollCmd）。 */
data class TocScrollCmd(val pos: Int = 0, val tick: Long = 0)

/**
 * TocScreen 完整版渲染所需的全部展示状态。
 *
 * app 端 [TocActivity] 在 `Content()` 内将自己的 `var` 状态字段打包为本数据类传入；
 * 桌面端不使用完整版 [TocScreen]，直接调用 [TocDrawerContent]。
 */
data class TocUiState(
    val book: Book?,
    val durChapterIndex: Int,
    val searching: Boolean,
    val searchKey: String,
    val chapters: List<BookChapter>,
    val collapsedVolumes: Set<Int>,
    val displayTitleMap: Map<String, String>,
    val cacheFileNames: Set<String>,
    val useReplace: Boolean,
    val countWords: Boolean,
    val chapterScroll: TocScrollCmd,
    val bookmarks: List<Bookmark>,
    val bookmarkScroll: TocScrollCmd,
    val isLocalBook: Boolean,
) {
    companion object {
        val Empty = TocUiState(
            book = null,
            durChapterIndex = 0,
            searching = false,
            searchKey = "",
            chapters = emptyList(),
            collapsedVolumes = emptySet(),
            displayTitleMap = emptyMap(),
            cacheFileNames = emptySet(),
            useReplace = false,
            countWords = false,
            chapterScroll = TocScrollCmd(),
            bookmarks = emptyList(),
            bookmarkScroll = TocScrollCmd(),
            isLocalBook = false,
        )
    }
}

/**
 * TocScreen 完整版的用户交互回调。
 *
 * app 端 [TocActivity] 实现本接口（已有同名方法直接 `override`）；
 * 桌面端不使用完整版 [TocScreen]。
 */
interface TocUiActions {
    fun onBack()
    fun setSearchMode(active: Boolean)
    fun setQuery(query: String)
    fun toggleVolume(volume: BookChapter)
    fun openChapter(chapter: BookChapter)
    fun reverseChapterList()
    fun toggleUseReplace()
    fun toggleCountWords()
    fun toggleSplitLongChapter()
    fun showTocRegexDialog()
    fun exportBookmark()
    fun exportBookmarkMd()
    fun showLog()
    fun openBookmark(bookmark: Bookmark)
    fun editBookmark(bookmark: Bookmark, pos: Int)
}

// ===== TocScreen 完整版（app 端 TocActivity 用）=====

/**
 * 目录页内容：标题栏(双 tab / 搜索态互斥) + HorizontalPager(目录/书签)。
 *
 * 下沉自 app 端原 `TocScreen(activity: TocActivity)`，将 `TocActivity` 直接依赖
 * 拆为 [state] (展示状态) + [actions] (交互回调)，去除 Android `Context` /
 * `longToastOnUi` 依赖。`BackHandler` 由调用方 (app TocActivity.Content) 自行
 * 注册 (shared/sharedUiMain 未引入 activity-compose 依赖)。
 *
 * 4 个增强能力（搜索/反转/卷折叠/字数显示）均通过 [state] + [actions] 接通：
 * - 搜索：[TocUiState.searching] / [TocUiState.searchKey] + [TocUiActions.setSearchMode] / [TocUiActions.setQuery]
 * - 反转：[TocUiActions.reverseChapterList] (溢出菜单)
 * - 卷折叠：[TocUiState.collapsedVolumes] + [TocUiActions.toggleVolume]
 * - 字数显示：[TocUiState.countWords] + 章节项 `wordCount` 文本
 */
@Composable
fun TocScreen(
    state: TocUiState,
    actions: TocUiActions,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        AppTitleBar(
            title = "",
            onBack = { actions.onBack() },
            titleContent = {
                if (state.searching) {
                    val focusRequester = remember { FocusRequester() }
                    AppSearchField(
                        value = state.searchKey,
                        onValueChange = { actions.setQuery(it) },
                        hint = stringResource(Res.string.search),
                        modifier = Modifier.focusRequester(focusRequester),
                    )
                    LaunchedEffect(Unit) {
                        runCatching { focusRequester.requestFocus() }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TocTab(
                            stringResource(Res.string.chapter_list),
                            pagerState.currentPage == 0
                        ) {
                            scope.launch {
                                if (eInk) pagerState.scrollToPage(0)
                                else pagerState.animateScrollToPage(0)
                            }
                        }
                        TocTab(stringResource(Res.string.bookmark), pagerState.currentPage == 1) {
                            scope.launch {
                                if (eInk) pagerState.scrollToPage(1)
                                else pagerState.animateScrollToPage(1)
                            }
                        }
                    }
                }
            },
            actions = { TocActions(state, actions, pagerState.currentPage) },
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = !eInk,
        ) { page ->
            if (page == 0) ChapterListPage(state, actions) else BookmarkPage(state, actions)
        }
    }
}

/** 对照 TabLayout：选中 accent、2dp 指示器随文字宽(isTabIndicatorFullWidth=false) */
@Composable
private fun TocTab(title: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .height(DesignTokens.viewHeightXl)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = title,
                color = if (selected) colors.accent else colors.primaryText,
                fontSize = 14.sp,
                maxLines = 1,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) colors.accent else Color.Transparent),
        )
    }
}

/** 溢出菜单按 tab 分组显隐，对照原 onMenuOpened */
@Composable
private fun TocActions(state: TocUiState, actions: TocUiActions, page: Int) {
    val colors = AppTheme.colors
    IconButton(onClick = { actions.setSearchMode(!state.searching) }) {
        Icon(
            painter = rememberPainter(
                if (state.searching) "ic_baseline_close" else "ic_search"
            ),
            contentDescription = stringResource(Res.string.search),
            tint = colors.primaryText,
        )
    }
    val book = state.book
    OverflowMenu { dismiss ->
        if (page == 1) {
            MenuItem(stringResource(Res.string.export)) { dismiss(); actions.exportBookmark() }
            MenuItem(stringResource(Res.string.export_md)) { dismiss(); actions.exportBookmarkMd() }
        } else {
            if (book?.isLocalTxt == true) {
                MenuItem(stringResource(Res.string.txt_toc_rule)) {
                    dismiss(); actions.showTocRegexDialog()
                }
                CheckItem(
                    stringResource(Res.string.split_long_chapter),
                    book.config.splitLongChapter,
                ) { dismiss(); actions.toggleSplitLongChapter() }
            }
            MenuItem(stringResource(Res.string.reverse_toc)) {
                dismiss(); actions.reverseChapterList()
            }
            CheckItem(stringResource(Res.string.use_replace), state.useReplace) {
                dismiss(); actions.toggleUseReplace()
            }
            CheckItem(stringResource(Res.string.load_word_count), state.countWords) {
                dismiss(); actions.toggleCountWords()
            }
        }
        MenuItem(stringResource(Res.string.log)) { dismiss(); actions.showLog() }
    }
}

// ===== 目录页 =====

@Composable
private fun ChapterListPage(state: TocUiState, actions: TocUiActions) {
    val listState = rememberLazyListState()
    val display by remember(state.chapters, state.collapsedVolumes) {
        derivedStateOf { buildDisplayList(state.chapters, state.collapsedVolumes) }
    }
    val scroll = state.chapterScroll
    LaunchedEffect(scroll) {
        if (display.isNotEmpty()) {
            listState.scrollToItem(scroll.pos.coerceIn(0, display.size - 1))
        }
    }
    Column(Modifier.fillMaxSize()) {
        // 提取不变的引用，避免在 lambda 内反复读 state
        val titleMap = state.displayTitleMap
        val cacheFiles = state.cacheFileNames
        val isLocal = state.isLocalBook
        val collapsedSet = state.collapsedVolumes
        val countWords = state.countWords
        val durIndex = state.durChapterIndex
        FastScrollLazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            items(display, key = { it.index }) { item ->
                // 缓存文件名, 避免每次重组重复计算
                val fileName = remember(item) { item.getFileName() }
                // 参数瘦身: 只传本项派生值(全稳定), state 任一无关字段变化时本项可跳过重组
                ChapterItem(
                    item = item,
                    title = titleMap[item.title] ?: item.title,
                    isDur = durIndex == item.index,
                    cached = isLocal || item.isVolume ||
                        cacheFiles.contains(fileName),
                    collapsed = item.index in collapsedSet,
                    countWords = countWords,
                    actions = actions,
                )
            }
        }
        ChapterInfoBar(state, actions, listState, display.size)
    }
}

@Composable
private fun ChapterItem(
    item: BookChapter,
    title: String,
    isDur: Boolean,
    cached: Boolean,
    collapsed: Boolean,
    countWords: Boolean,
    actions: TocUiActions,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .then(
                // 卷名突出显示，普通章节保持 ripple
                if (item.isVolume) Modifier.background(rememberColor("btn_bg")) else Modifier
            )
            .combinedClickable(
                onClick = {
                    if (item.isVolume) actions.toggleVolume(item) else actions.openChapter(item)
                },
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.isVip && !item.isPay) {
            Icon(
                painter = painterResource(Res.drawable.ic_lock_outline),
                contentDescription = null,
                tint = colors.secondaryText,
                modifier = Modifier
                    .size(24.dp)
                    .padding(end = 8.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isDur) colors.accent else colors.primaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val showWordCount =
                countWords && !item.wordCount.isNullOrEmpty() && !item.isVolume
            val showTag = !item.tag.isNullOrEmpty() && !item.isVolume
            if (showWordCount || showTag) {
                Row {
                    if (showWordCount) {
                        Text(
                            text = item.wordCount.orEmpty(),
                            color = colors.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                    if (showTag) {
                        Text(
                            text = item.tag.orEmpty(),
                            color = colors.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // 右侧图标优先级：卷折叠箭头 > 当前章 > 未缓存云朵；invisible 时占位
        Box(
            Modifier
                .size(24.dp)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                item.isVolume -> Icon(
                    painter = rememberPainter(
                        if (collapsed) "ic_expand_more" else "ic_expand_less"
                    ),
                    contentDescription = null,
                    tint = colors.secondaryText,
                )

                isDur -> Icon(
                    painter = painterResource(Res.drawable.ic_check),
                    contentDescription = null,
                    tint = colors.secondaryText,
                )

                !cached -> Icon(
                    painter = painterResource(Res.drawable.ic_outline_cloud_24),
                    contentDescription = null,
                    tint = colors.secondaryText,
                )
            }
        }
    }
}

/** 底部当前章信息栏：底栏色，文字色随底栏亮度反推(对照 getPrimaryTextColor) */
@Composable
private fun ChapterInfoBar(
    state: TocUiState,
    actions: TocUiActions,
    listState: LazyListState,
    itemCount: Int,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val barText = if (ColorUtils.isColorLight(colors.bottomBackground.toArgb())) {
        Color(0xDE000000)
    } else {
        Color.White
    }
    val book = state.book
    // Book.equals 只比 bookUrl, 用 book 当 key 会漏掉进度变化, 故按会变的标量取 key
    val info = remember(book?.durChapterTitle, book?.durChapterIndex, book?.totalChapterNum) {
        book?.let {
            "${it.durChapterTitle}(${it.durChapterIndex + 1}/${it.simulatedTotalChapterNum()})"
        }.orEmpty()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bottomBackground)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = info,
            color = barText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .clickable {
                    scope.launch {
                        if (itemCount > 0) {
                            listState.scrollToItem(state.durChapterIndex.coerceIn(0, itemCount - 1))
                        }
                    }
                }
                .padding(horizontal = 16.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
        IconButton(
            onClick = { scope.launch { listState.scrollToItem(0) } },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_arrow_drop_up),
                contentDescription = stringResource(Res.string.go_to_top),
                tint = barText,
            )
        }
        IconButton(
            onClick = { scope.launch { if (itemCount > 0) listState.scrollToItem(itemCount - 1) } },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_arrow_drop_down),
                contentDescription = stringResource(Res.string.go_to_bottom),
                tint = barText,
            )
        }
    }
}

/** 卷折叠：隐藏被折叠卷名到下一卷名之间的章节 */
private fun buildDisplayList(all: List<BookChapter>, collapsed: Set<Int>): List<BookChapter> {
    if (collapsed.isEmpty()) return all
    val out = ArrayList<BookChapter>(all.size)
    var hide = false
    for (item in all) {
        if (item.isVolume) {
            hide = item.index in collapsed
            out.add(item)
        } else if (!hide) {
            out.add(item)
        }
    }
    return out
}

// ===== 书签页 =====

@Composable
private fun BookmarkPage(state: TocUiState, actions: TocUiActions) {
    val listState = rememberLazyListState()
    val bookmarks = state.bookmarks
    val scroll = state.bookmarkScroll
    LaunchedEffect(scroll) {
        if (bookmarks.isNotEmpty()) {
            listState.scrollToItem(scroll.pos.coerceIn(0, bookmarks.size - 1))
        }
    }
    val navPad = WindowInsets.navigationBars.asPaddingValues()
    FastScrollLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = navPad.calculateBottomPadding(),
        ),
    ) {
        itemsIndexed(bookmarks, key = { _, item -> item.time }) { index, item ->
            BookmarkItem(actions, item, index)
        }
    }
}

// state 参数已移除: 本项不读 TocUiState, 避免无关状态变化触发重组
@Composable
private fun BookmarkItem(
    actions: TocUiActions,
    item: Bookmark,
    pos: Int,
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { actions.openBookmark(item) },
                onLongClick = { actions.editBookmark(item, pos) },
            )
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = item.chapterName,
            color = colors.primaryText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
        )
        if (item.bookText.isNotEmpty()) {
            Text(
                text = item.bookText,
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
            )
        }
        if (item.content.isNotEmpty()) {
            Text(
                text = item.content,
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
            )
        }
    }
}

// ===== 菜单项 =====

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = AppTheme.colors.primaryText)
    }
}

/** 勾选项：MD2 方框(右置) */
@Composable
private fun CheckItem(text: String, checked: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = colors.primaryText)
        Spacer(Modifier.width(12.dp))
        AppMenuCheckbox(checked = checked)
    }
}

// ===== TocDrawerContent (public, 桌面端用) =====

/**
 * 桌面端阅读页章节目录侧栏内容（KMP 版，替代原 ChapterListDrawer）。
 *
 * 内部维护 D 过渡实现的 4 个增强能力：
 * - **搜索框**：顶部 [AppSearchField]，`chapterList.filter { it.title.contains(query, ignoreCase = true) }`
 * - **反转按钮**：标题栏右侧 IconButton，`displayList.reversed()`
 * - **卷折叠**：[mutableStateMapOf] 记录卷展开状态，卷名行点击切换
 * - **字数显示**：章节项右侧 `StringUtils.wordCountFormat(chapter.wordCount)`
 *
 * # 对照
 *
 * 对照 app 端 `TocScreen#ChapterListPage`:
 * - app 端用 [TocActivity] 持有状态, 桌面端用本 Composable 内部 `remember` 维护
 * - 当前章节高亮: app 端 `colors.accent`, 这里用 [AppTheme.colors.accent] (与 app 端一致)
 * - 卷名突出: app 端 `rememberColor("btn_bg")`, 这里同样
 * - 点击回调: app 端 `actions.openChapter(item)` → 这里收敛为 [onChapterClick] (index) 回调
 *
 * # 职责
 *
 * 仅渲染 drawer 内容 (drawerContent + 顶部搜索/反转栏 + LazyColumn + 章节项),
 * 由调用方 ([io.legado.desktop.ui.reader.ReaderScreen]) 用 `ModalDrawer`
 * 包裹并传入 `drawerContent = { TocDrawerContent(...) }`。
 *
 * @param chapterList 章节列表 (来自 [io.legado.app.ui.book.read.ReadBookViewModelShared.chapterList])
 * @param currentIndex 当前章节索引, 用于高亮 + 首次打开自动滚动定位
 * @param onChapterClick 章节点击回调, 参数为章节 index; 调用方负责调
 *   [io.legado.app.ui.book.read.ReadBookViewModelShared.loadChapter] 跳转 + 关闭 drawer
 * @param modifier Modifier, 默认 [Modifier.fillMaxSize]
 */
@Composable
fun TocDrawerContent(
    chapterList: List<BookChapter>,
    currentIndex: Int,
    onChapterClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val listState = rememberLazyListState()
    // D 过渡实现的 4 个增强状态（桌面端内部维护, app 端完整版由 TocActivity 管理）
    var searchQuery by remember { mutableStateOf("") }
    var reversed by remember { mutableStateOf(false) }
    val collapsedVolumes = remember { mutableStateMapOf<Int, Boolean>() }

    // 1. 搜索过滤
    val filtered = remember(chapterList, searchQuery) {
        if (searchQuery.isBlank()) chapterList
        else chapterList.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }
    // 2. 反转
    val ordered = remember(filtered, reversed) { if (reversed) filtered.reversed() else filtered }
    // 3. 卷折叠
    val collapsedKeys = collapsedVolumes.entries.filter { it.value }.map { it.key }.toSet()
    val displayList = remember(ordered, collapsedKeys) { buildDisplayList(ordered, collapsedKeys) }

    // 首次打开 drawer 时自动滚动到当前章节 (对照 app 端 TocScreen `LaunchedEffect(scroll)`)
    LaunchedEffect(chapterList, currentIndex) {
        if (displayList.isNotEmpty()) {
            val target = displayList.indexOfFirst { it.index == currentIndex }.coerceAtLeast(0)
            listState.scrollToItem(target)
        }
    }

    Column(modifier = modifier) {
        // 顶部搜索栏 + 反转按钮 (对照 D 过渡实现)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(DesignTokens.viewHeightXl)
                .background(colors.background)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                hint = stringResource(Res.string.search),
                modifier = Modifier.weight(1f),
            )
            // 反转按钮
            IconButton(onClick = { reversed = !reversed }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_sort),
                    contentDescription = stringResource(Res.string.reverse_toc),
                    tint = if (reversed) colors.accent else colors.primaryText,
                )
            }
        }

        // 章节列表
        FastScrollLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(displayList, key = { it.index }) { chapter ->
                TocDrawerItem(
                    chapter = chapter,
                    isCurrent = chapter.index == currentIndex,
                    isCollapsed = collapsedVolumes[chapter.index] == true,
                    onClick = {
                        if (chapter.isVolume) {
                            // 卷名行点击切换折叠
                            collapsedVolumes[chapter.index] = !(collapsedVolumes[chapter.index] ?: false)
                        } else {
                            onChapterClick(chapter.index)
                        }
                    },
                )
            }
        }
    }
}

/**
 * 桌面端章节列表项 (对照 app 端 TocScreen#ChapterItem 简化版, 增加字数显示)。
 *
 * @param chapter 章节实体
 * @param isCurrent 是否当前章节 (高亮 accent + 右侧勾选图标)
 * @param isCollapsed 卷折叠状态 (仅卷名行有效, 决定展开/折叠箭头)
 * @param onClick 点击回调 (卷名行切换折叠, 普通章节跳转)
 */
@Composable
private fun TocDrawerItem(
    chapter: BookChapter,
    isCurrent: Boolean,
    isCollapsed: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    // 卷名加背景色突出显示 (对照 app 端 TocScreen 用 rememberColor("btn_bg"))
    val rowModifier = if (chapter.isVolume) {
        Modifier
            .fillMaxWidth()
            .background(rememberColor("btn_bg"))
    } else {
        Modifier.fillMaxWidth()
    }

    Row(
        modifier = rowModifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 章节标题 + 卷名加粗
        Text(
            text = chapter.title,
            color = if (isCurrent) colors.accent else colors.primaryText,
            fontSize = 14.sp,
            fontWeight = if (chapter.isVolume) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // D 过渡实现: 章节项右侧显示字数 (StringUtils.wordCountFormat 格式化)
        if (!chapter.isVolume && !chapter.wordCount.isNullOrEmpty()) {
            Text(
                text = StringUtils.wordCountFormat(chapter.wordCount),
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        // 右侧图标: 卷折叠箭头 / 当前章勾选
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                chapter.isVolume -> Icon(
                    painter = rememberPainter(if (isCollapsed) "ic_expand_more" else "ic_expand_less"),
                    contentDescription = null,
                    tint = colors.secondaryText,
                )

                isCurrent -> Icon(
                    painter = painterResource(Res.drawable.ic_check),
                    contentDescription = null,
                    tint = colors.secondaryText,
                )
            }
        }
    }
}
