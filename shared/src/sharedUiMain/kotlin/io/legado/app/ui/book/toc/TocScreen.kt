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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import io.legado.app.ui.compose.component.FastScrollLazyVerticalGrid
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.rememberResponsiveColumns
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.ColorUtils
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
 * app 端 [TocActivity] 在 `Content()` 内将自己的 `var` 状态字段打包为本数据类传入。
 * 四端目录页与目录弹窗共用同一份 [TocScreen] (弹窗形态见 TocRoute#TocDialogHost → TocContent)。
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
 * TocScreen 的用户交互回调。
 *
 * 四端目录页 (TocRoute) 与弹窗 (TocDialogHost) 均通过本接口对接用户交互。
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
    fun editBookmark(bookmark: Bookmark)
}

// ===== TocScreen =====

/**
 * 目录页内容：标题栏(双 tab / 搜索态互斥) + HorizontalPager(目录/书签)。
 *
 * 对照原版目录页设计，将 UI 状态与交互解耦为 [state] (展示状态) + [actions] (交互回调)。
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
    val listState = rememberLazyGridState()
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
        // 与发现页同款按容器宽度自动分列 (rememberResponsiveColumns(1): 400dp→1列,
        // 600dp→2列…); 卷名行跨满整行, 窄屏单列时与原来完全一致
        FastScrollLazyVerticalGrid(
            columns = rememberResponsiveColumns(1),
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            items(
                display,
                key = { it.index },
                span = { if (it.isVolume) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            ) { item ->
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
            // 基准 56dp, 双行(标题+字数/标签)或大字下内容超高时自动增高不裁剪
            .heightIn(min = DesignTokens.viewHeightMax)
            .then(
                // 卷名突出显示，普通章节保持 ripple
                if (item.isVolume) Modifier.background(rememberColor("btn_bg")) else Modifier
            )
            .combinedClickable(
                onClick = {
                    if (item.isVolume) actions.toggleVolume(item) else actions.openChapter(item)
                },
            )
            .padding(horizontal = 8.dp),
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
    listState: LazyGridState,
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
        items(bookmarks, key = { it.time }) { item ->
            BookmarkItem(actions, item)
        }
    }
}

// state 参数已移除: 本项不读 TocUiState, 避免无关状态变化触发重组
@Composable
private fun BookmarkItem(
    actions: TocUiActions,
    item: Bookmark,
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            // 基准 56dp; 书签正文两行以上时内容撑高
            .heightIn(min = DesignTokens.viewHeightMax)
            .combinedClickable(
                onClick = { actions.openBookmark(item) },
                onLongClick = { actions.editBookmark(item) },
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
