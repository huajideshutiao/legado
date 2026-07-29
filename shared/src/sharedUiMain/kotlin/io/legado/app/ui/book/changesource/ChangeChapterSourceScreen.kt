package io.legado.app.ui.book.changesource

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.platform.rememberString
import kotlinx.coroutines.launch

/**
 * 章节换源 UI 交互回调。
 *
 * 实际业务 (搜索/筛选/启停/取目录/取正文) 留在 app 端 ViewModel, Route 层
 * 将 navigator 与平台 ViewModel 桥接到本接口。
 */
interface ChangeChapterSourceUiActions {
    /** 返回 (导航器 pop) */
    fun onBack()

    /** 启停搜索 (标题栏刷新按钮) */
    fun onStartStop()

    /** 筛选关键词变更 (searchMode 输入) */
    fun onScreen(key: String)

    /** 切换筛选模式 (放大镜按钮) */
    fun onSearchModeChange(enabled: Boolean)

    /** 点击源条目 (打开 toc 预览) */
    fun onItemClick(book: SearchBook)
}

/**
 * 章节换源溢出菜单回调 (对照 app 端 Dialog.Content 第 181-212 行菜单结构)。
 *
 * 6 个菜单项: BookSourceManage / CheckAuthor / LoadWordCount / LoadInfo / LoadToc / Group。
 * ChangeChapterSourceDialog 没有 RefreshList 和 Close 菜单项。
 */
interface ChangeChapterSourceMenuActions {
    fun onBookSourceManage()
    fun onCheckAuthorChange(value: Boolean)
    fun onLoadWordCountChange(value: Boolean)
    fun onLoadInfoChange(value: Boolean)
    fun onLoadTocChange(value: Boolean)
}

/**
 * 章节换源列表项长按菜单回调 (对照 app 端 SearchBookItem 6 个操作)。
 */
interface ChangeChapterSourceItemActions {
    fun getScore(book: SearchBook): Int
    fun setScore(book: SearchBook, score: Int)
    fun onTop(book: SearchBook)
    fun onBottom(book: SearchBook)
    fun onEdit(book: SearchBook)
    fun onDisable(book: SearchBook)
    fun onDelete(book: SearchBook)
}

/**
 * 章节换源页 (对照 app 端 [ChangeChapterSourceDialog])。
 *
 * 下沉自 Dialog 形态: 标题栏 (复用 [ChangeSourceTitleBar]) + 搜索进度条
 * (复用 [ChangeSourceRefreshBar]) + 源列表 ([FastScrollLazyColumn] + [SearchBookItem]
 * 完整版含点赞/长按菜单) + 底栏 (复用 [ChangeSourceBottomBar]) + toc 预览覆盖层
 * ([ChapterTocPanel]) + 分组选择对话框 ([GroupPickerDialog])。
 *
 * 与 [ChangeSourceScreen] 同构, 差异:
 * - 标题用 [ChangeChapterSourceUiState.chapterTitle];
 * - 列表项用完整 [SearchBookItem] (对照 app 端 Dialog 第 222-235 行);
 * - 在 Box 中叠加 toc 预览覆盖层 [ChapterTocPanel] (对照 Dialog 第 213-263 行);
 * - 菜单 6 项 (BookSourceManage / CheckAuthor / LoadWordCount / LoadInfo / LoadToc / Group,
 *   对照 Dialog 第 181-212 行, 不含 RefreshList / Close)。
 *
 * @param state UI 状态 (由 [ChangeChapterSourceScreenModel] 持有)
 * @param actions 交互回调 (由 Route 层桥接到 navigator + 平台 ViewModel)
 * @param menuActions 溢出菜单回调 (由 Route 层桥接到 platform 持久化 + ViewModel 刷新)
 * @param itemActions 列表项长按菜单回调 (由 Route 层桥接到 ViewModel)
 * @param searchGroup 当前选中分组 (GroupPickerDialog 选中态)
 * @param onSearchGroupChange 分组变更 (Route 层 dispatch SearchGroupChanged)
 * @param onShowGroupPicker 显示分组选择对话框
 * @param showGroupPicker 分组选择对话框是否显示
 * @param onGroupPickerDismiss 分组选择对话框关闭
 * @param onGroupPickerSelect 分组选择对话框选中 (Route 层做 stopSearch/refresh/startSearch)
 * @param groups 启用分组列表
 * @param onTocHide 隐藏 toc 预览覆盖层
 * @param onClickChapter 点击章节 (Route 层走 getContent + navigator.pop)
 */
@Composable
fun ChangeChapterSourceScreen(
    state: ChangeChapterSourceUiState,
    actions: ChangeChapterSourceUiActions,
    menuActions: ChangeChapterSourceMenuActions,
    itemActions: ChangeChapterSourceItemActions,
    searchGroup: String,
    onSearchGroupChange: (String) -> Unit,
    onShowGroupPicker: () -> Unit,
    showGroupPicker: Boolean,
    onGroupPickerDismiss: () -> Unit,
    onGroupPickerSelect: (String) -> Unit,
    groups: List<String>,
    onTocHide: () -> Unit,
    onClickChapter: (BookChapter, String?) -> Unit,
) {
    // 筛选模式 / 关键词为本地 UI 状态, 与 app 端 Dialog 同样用 rememberSaveable 持久化
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var screenKey by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 对照 AdapterDataObserver：首条变化(插入/移动到 0)回滚到顶
    LaunchedEffect(state.sources.firstOrNull()?.bookUrl) {
        if (state.sources.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        ChangeSourceTitleBar(
            title = state.chapterTitle,
            subtitle = null,
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
            TextMenuItem(rememberString("book_source_manage")) {
                dismiss()
                menuActions.onBookSourceManage()
            }
            CheckMenuItem(rememberString("checkAuthor"), state.checkAuthor) {
                dismiss()
                menuActions.onCheckAuthorChange(!state.checkAuthor)
            }
            CheckMenuItem(rememberString("load_word_count"), state.loadWordCount) {
                dismiss()
                menuActions.onLoadWordCountChange(!state.loadWordCount)
            }
            CheckMenuItem(rememberString("load_info"), state.loadInfo) {
                dismiss()
                menuActions.onLoadInfoChange(!state.loadInfo)
            }
            CheckMenuItem(rememberString("load_toc"), state.loadToc) {
                dismiss()
                menuActions.onLoadTocChange(!state.loadToc)
            }
            GroupMenuItem(
                title = rememberString("group"),
                dismissParent = dismiss,
                onShowGroupPicker = onShowGroupPicker,
            )
        }
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                ChangeSourceRefreshBar(state.isLoading)
                FastScrollLazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    items(state.sources, key = { it.bookUrl }) { book ->
                        SearchBookItem(
                            book = book,
                            isCurSource = book.bookUrl == state.curBookUrl,
                            loadWordCount = state.loadWordCount,
                            getScore = { itemActions.getScore(book) },
                            setScore = { itemActions.setScore(book, it) },
                            onClick = { actions.onItemClick(book) },
                            onTop = { itemActions.onTop(book) },
                            onBottom = { itemActions.onBottom(book) },
                            onEdit = { itemActions.onEdit(book) },
                            onDisable = { itemActions.onDisable(book) },
                            onDelete = { itemActions.onDelete(book) },
                        )
                    }
                }
                ChangeSourceBottomBar(
                    durText = state.book?.originName ?: "",
                    onDurClick = {
                        val index = state.sources.indexOfFirst { it.bookUrl == state.curBookUrl }
                        if (index >= 0) scope.launch {
                            listState.scrollToItem(index, with(density) { -60.dp.roundToPx() })
                        }
                    },
                    onTop = { scope.launch { listState.scrollToItem(0) } },
                    onBottom = {
                        scope.launch {
                            if (state.sources.isNotEmpty()) {
                                listState.scrollToItem(state.sources.lastIndex)
                            }
                        }
                    },
                )
            }
            if (state.tocVisible) {
                ChapterTocPanel(
                    toc = state.tocList,
                    durChapterIndex = state.durChapterIndex,
                    loading = state.tocLoading,
                    onHide = onTocHide,
                    onClickChapter = onClickChapter,
                )
            }
        }
    }
    // 分组选择独立 Dialog: 弹出时居中显示, 避免原嵌套 Popup 错位
    if (showGroupPicker) {
        GroupPickerDialog(
            groups = groups,
            selectedGroup = searchGroup,
            onDismiss = onGroupPickerDismiss,
            onSelect = { group ->
                onGroupPickerDismiss()
                onSearchGroupChange(group)
                onGroupPickerSelect(group)
            },
        )
    }
}
