package io.legado.desktop.ui.book.changesource

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.primaryStr
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.changesource.ChangeBookSourceViewModelShared
import io.legado.app.ui.book.changesource.ChangeSourceBottomBar
import io.legado.app.ui.book.changesource.ChangeSourceRefreshBar
import io.legado.app.ui.book.changesource.ChangeSourceTitleBar
import io.legado.app.ui.book.changesource.ChapterTocPanel
import io.legado.app.ui.book.changesource.CheckMenuItem
import io.legado.app.ui.book.changesource.GroupMenuItem
import io.legado.app.ui.book.changesource.GroupPickerDialog
import io.legado.app.ui.book.changesource.SearchBookItem
import io.legado.app.ui.book.changesource.TextMenuItem
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 桌面端章节换源 Screen (对照 app 端 ChangeChapterSourceDialog)。
 *
 * # 背景
 *
 * app 端 [io.legado.app.ui.book.changesource.ChangeChapterSourceDialog] 用于单章正文加载失败时,
 * 从其他源获取该章节正文替换当前阅读页内容 (不改整书源)。desktop 端原仅有
 * [ChangeSourceScreen] (整书换源, 走 migrateBook 迁移), 缺章节级换源入口。
 *
 * # 复用 shared 实现
 *
 * 章节换源专属能力已下沉到 shared commonMain [ChangeBookSourceViewModelShared]:
 * - [ChangeBookSourceViewModelShared.chapterIndex] / [ChangeBookSourceViewModelShared.chapterTitle]
 *   字段 (initData 6 参数重载写入);
 * - [ChangeBookSourceViewModelShared.getToc] 取新源目录;
 * - [ChangeBookSourceViewModelShared.getContent] 取该源章节正文。
 *
 * UI 组件复用 shared/sharedUiMain [ChangeSourceTitleBar] / [SearchBookItem] /
 * [ChangeSourceRefreshBar] / [ChangeSourceBottomBar] / [ChapterTocPanel] / 各 MenuItem,
 * 与 [ChangeSourceScreen] 完全一致, 仅交互流不同:
 * - 选中源 → openToc 显示 [ChapterTocPanel] 目录预览 (整书换源直接 getToc+migrateBook);
 * - 点击章节 → getContent 取正文 → [onReplaceContent] 回调宿主替换当前阅读页正文
 *   (整书换源走 [ChangeSourceScreen.onChangeSource] 做整书迁移)。
 *
 * # 与 app 端差异
 *
 * - 章节定位: app 端用 `BookHelp.getDurChapter(index, title, toc)` (jaccard 相似度),
 *   依赖未下沉的 EscapeUtils / StringUtils; 桌面端用 [findChapterIndex] 简化匹配
 *   (标题相等 → 标题包含 → chapterIndex → 末章), 与 [DesktopChangeBookSourcePlatform.getDurChapter]
 *   取末章的简化风格一致;
 * - 无编辑书源入口 (BookSourceEditScreen 未下沉, onEdit no-op);
 * - toastOnUi 用 [Toasters] (桌面端统一 toast 通道)。
 *
 * @param book 待换源书籍 (用 name/author/bookUrl 匹配搜索结果, 作为 oldBook 传 initData)
 * @param chapterIndex 当前章节序号 (在新源目录中定位对应章节, 对照 app 端 viewModel.chapterIndex)
 * @param chapterTitle 当前章节标题 (标题栏显示 + 章节定位, 对照 app 端 viewModel.chapterTitle)
 * @param onBack 返回回调 (关闭覆盖层)
 * @param onReplaceContent 选中源+章节后取到的正文回调 → 宿主 saveText + 重载章节
 */
@Composable
fun ChangeChapterSourceScreen(
    book: Book,
    chapterIndex: Int,
    chapterTitle: String,
    onBack: () -> Unit,
    onReplaceContent: (content: String) -> Unit,
) {
    // 注入 desktop 平台 Provider (shared AppTheme / rememberString 依赖)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            ChangeChapterSourceContent(
                book = book,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                onBack = onBack,
                onReplaceContent = onReplaceContent,
            )
        }
    }
}

@Composable
private fun ChangeChapterSourceContent(
    book: Book,
    chapterIndex: Int,
    chapterTitle: String,
    onBack: () -> Unit,
    onReplaceContent: (content: String) -> Unit,
) {
    // shared VM (KMP), remember 缓存避免重组时重建
    val scope = rememberCoroutineScope()
    val platform = remember { DesktopChangeBookSourcePlatform() }
    val viewModel = remember { ChangeBookSourceViewModelShared(scope, platform) }
    // 文案标签 (rememberString 是 @Composable, 顶层缓存后供菜单项 / 进度文本引用)
    val searchedCountProgressTemplate = rememberString("searched_count_progress")
    val bookSourceManageLabel = rememberString("book_source_manage")
    val checkAuthorLabel = rememberString("checkAuthor")
    val loadWordCountLabel = rememberString("load_word_count")
    val loadInfoLabel = rememberString("load_info")
    val loadTocLabel = rememberString("load_toc")
    val groupLabel = rememberString("group")

    // UI 状态 (对照 app 端 ChangeChapterSourceDialog.Content 内的 remember 变量)
    var items by remember { mutableStateOf(emptyList<SearchBook>()) }
    var searching by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf(emptyList<String>()) }
    // 分组二级菜单独立 Dialog 状态：避免嵌套 Popup 位置错乱
    var showGroupPicker by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var screenKey by remember { mutableStateOf("") }
    var checkAuthor by remember { mutableStateOf(platform.changeSourceCheckAuthor) }
    var loadInfo by remember { mutableStateOf(platform.changeSourceLoadInfo) }
    var loadToc by remember { mutableStateOf(platform.changeSourceLoadToc) }
    var loadWordCount by remember { mutableStateOf(platform.changeSourceLoadWordCount) }
    val searchGroup = platform.searchGroup
    var durText by remember { mutableStateOf(book.originName) }
    val listState = rememberLazyListState()

    // toc 预览覆盖层状态 (对照 app 端 ChangeChapterSourceDialog tocVisible / tocLoading / tocList / durChapterIndex)
    var tocVisible by remember { mutableStateOf(false) }
    var tocLoading by remember { mutableStateOf(false) }
    var tocList by remember { mutableStateOf<List<BookChapter>?>(null) }
    var durChapterIndex by remember { mutableIntStateOf(0) }
    // 当前打开 toc 的源对应的 Book (clickChapter 时 getContent 用, 对照 app 端 searchBook.toBook())
    var tocBook by remember { mutableStateOf<Book?>(null) }

    // 初始化数据 (用 shared.initData 6 参数重载, fromReadBookActivity=true 因从阅读页进入,
    // 接入 chapterIndex / chapterTitle 字段, 与 app 端 ChangeChapterSourceViewModel.initData 对齐)
    LaunchedEffect(book.bookUrl) {
        viewModel.initData(book.name, book.author, true, book, chapterIndex, chapterTitle)
        viewModel.startSearch()
    }

    // 收集搜索结果 (对照 app 端 viewModel.searchDataFlow.conflate().collect)
    LaunchedEffect(Unit) {
        viewModel.searchDataFlow.conflate().collect {
            items = it
            delay(1000)
        }
    }

    // 收集搜索状态 (对照 app 端 viewModel.searchStateData.observe)
    LaunchedEffect(Unit) {
        viewModel.searchState.collect { searching = it }
    }

    // 收集换源进度 (对照 app 端 viewModel.changeSourceProgress.drop(1).collect)
    LaunchedEffect(Unit) {
        viewModel.changeSourceProgress.drop(1).collect { (count, name) ->
            durText = searchedCountProgressTemplate.format(items.size, count, viewModel.totalSourceCount, name)
            delay(500)
        }
    }

    // 收集启用书源分组列表 (供 GroupMenuItem 展示)
    LaunchedEffect(Unit) {
        AppDbProviders.get().bookSourceDao.flowEnabledGroups().conflate().collect { groups = it }
    }

    // 首条变化回滚到顶 (对照 AdapterDataObserver: 首条插入/移动到 0 时 scrollToItem(0))
    LaunchedEffect(items.firstOrNull()?.bookUrl) {
        if (items.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        ChangeSourceTitleBar(
            title = chapterTitle,
            subtitle = null,
            searchMode = searchMode,
            screenKey = screenKey,
            searching = searching,
            onBack = {
                // 返回键先收起 toc 再关闭 (对照 app 端 onBackPressedDispatcher: tocVisible 先收起)
                if (tocVisible) {
                    tocVisible = false
                } else {
                    onBack()
                }
            },
            onSearchModeChange = { searchMode = it },
            onScreen = { key ->
                screenKey = key
                viewModel.screen(key)
            },
            onStartStop = { viewModel.startOrStopSearch() },
        ) { dismiss ->
            // 溢出菜单 (对照 app 端 ChangeChapterSourceDialog 菜单项)
            TextMenuItem(bookSourceManageLabel) {
                dismiss()
                // TODO: 切到桌面端 BookSourceScreen 路由, 由宿主提供
            }
            CheckMenuItem(checkAuthorLabel, checkAuthor) {
                dismiss()
                checkAuthor = !checkAuthor
                // 写回 PreferenceProviders 持久化 (platform var setter 写 prefs)
                platform.changeSourceCheckAuthor = checkAuthor
                viewModel.refresh()
            }
            CheckMenuItem(loadWordCountLabel, loadWordCount) {
                dismiss()
                loadWordCount = !loadWordCount
                platform.changeSourceLoadWordCount = loadWordCount
                viewModel.onLoadWordCountChecked(loadWordCount)
            }
            CheckMenuItem(loadInfoLabel, loadInfo) {
                dismiss()
                loadInfo = !loadInfo
                platform.changeSourceLoadInfo = loadInfo
            }
            CheckMenuItem(loadTocLabel, loadToc) {
                dismiss()
                loadToc = !loadToc
                platform.changeSourceLoadToc = loadToc
            }
            GroupMenuItem(
                title = if (searchGroup.isEmpty()) groupLabel else "$groupLabel($searchGroup)",
                dismissParent = dismiss,
                onShowGroupPicker = { showGroupPicker = true },
            )
        }
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                ChangeSourceRefreshBar(searching)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    items(items, key = { it.bookUrl }) { searchBook ->
                        SearchBookItem(
                            book = searchBook,
                            isCurSource = searchBook.bookUrl == book.bookUrl,
                            loadWordCount = loadWordCount,
                            getScore = { viewModel.getBookScore(searchBook) },
                            setScore = { viewModel.setBookScore(searchBook, it) },
                            onClick = {
                                // 选中源 → 打开 toc 预览覆盖层 (对照 app 端 openToc)
                                // 取 Book 实例: 优先用 bookMap (loadBookToc 缓存), 否则 toBook 转换
                                val newBook = viewModel.bookMap[searchBook.primaryStr()] ?: searchBook.toBook()
                                tocBook = newBook
                                tocList = null
                                tocVisible = true
                                tocLoading = true
                                viewModel.getToc(
                                    book = newBook,
                                    onSuccess = { toc, _ ->
                                        // 定位对应章节 (对照 app 端 BookHelp.getDurChapter)
                                        durChapterIndex = findChapterIndex(toc, chapterIndex, chapterTitle)
                                        tocLoading = false
                                        tocList = toc
                                    },
                                    onError = { e ->
                                        tocVisible = false
                                        tocLoading = false
                                        AppLog.put(jvmGetString("change_chapter_source_load_toc_error_log", e), e)
                                        Toasters.get().toast(e.localizedMessage ?: jvmGetString("error_load_toc"))
                                    },
                                )
                            },
                            onTop = { viewModel.topSource(searchBook) },
                            onBottom = { viewModel.bottomSource(searchBook) },
                            onEdit = {
                                // TODO: 桌面端 BookSourceEditScreen 未下沉, 编辑书源路由待实现
                            },
                            onDisable = { viewModel.disableSource(searchBook) },
                            onDelete = { viewModel.del(searchBook) },
                        )
                    }
                }
                ChangeSourceBottomBar(
                    durText = durText,
                    onDurClick = {
                        // 滚动到当前源 (对照 app 端 onDurClick: indexOfFirst + scrollToItem)
                        val index = items.indexOfFirst { it.bookUrl == book.bookUrl }
                        if (index >= 0) {
                            scope.launch { listState.scrollToItem(index) }
                        }
                    },
                    onTop = { scope.launch { listState.scrollToItem(0) } },
                    onBottom = {
                        scope.launch { if (items.isNotEmpty()) listState.scrollToItem(items.lastIndex) }
                    },
                )
            }
            // 章节目录预览覆盖层 (对照 app 端 ChangeChapterSourceDialog ChapterTocPanel)
            if (tocVisible) {
                ChapterTocPanel(
                    toc = tocList,
                    durChapterIndex = durChapterIndex,
                    loading = tocLoading,
                    onHide = { tocVisible = false },
                    onClickChapter = { bookChapter, nextChapterUrl ->
                        // 点击章节 → 取该源正文 → onReplaceContent 替换当前阅读页 (对照 app 端 clickChapter)
                        val targetBook = tocBook
                        if (targetBook != null) {
                            tocLoading = true
                            viewModel.getContent(
                                book = targetBook,
                                chapter = bookChapter,
                                nextChapterUrl = nextChapterUrl,
                                success = { content ->
                                    tocLoading = false
                                    onReplaceContent(content)
                                    onBack()
                                },
                                error = { msg ->
                                    tocLoading = false
                                    tocVisible = false
                                    Toasters.get().toast(msg)
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    // 分组选择独立 Dialog：弹出时居中显示，避免原嵌套 Popup 错位
    if (showGroupPicker) {
        GroupPickerDialog(
            groups = groups,
            selectedGroup = searchGroup,
            onDismiss = { showGroupPicker = false },
            onSelect = { group ->
                showGroupPicker = false
                // 分组切换: 写回 platform.searchGroup + 停搜索 + 刷新 + 重启搜索
                // (对照 app 端 ChangeChapterSourceDialog.onGroupSelected)
                platform.searchGroup = group
                scope.launch {
                    viewModel.stopSearch()
                    if (viewModel.refresh()) {
                        viewModel.startSearch()
                    }
                }
            },
        )
    }
}

/**
 * 章节定位 (对照 app 端 `BookHelp.getDurChapter(chapterIndex, chapterTitle, toc)`)。
 *
 * app 端用 `EscapeUtils.jaccardSimilarity` + `StringUtils.fullToHalf` 做标题相似度匹配,
 * 依赖未下沉的 Android 专属字符串处理。桌面端简化匹配策略:
 * 1. 标题完全相等;
 * 2. 标题包含 (双向, 容忍新源标题带"第X章"前缀等差异);
 * 3. chapterIndex (章节序号, 在新源目录可能错位, 作为兜底);
 * 4. 末章 (与 [DesktopChangeBookSourcePlatform.getDurChapter] 简化一致)。
 *
 * @param toc 新源章节列表
 * @param chapterIndex 原章节序号
 * @param chapterTitle 原章节标题
 * @return 新源目录中对应章节的索引
 */
private fun findChapterIndex(toc: List<BookChapter>, chapterIndex: Int, chapterTitle: String): Int {
    if (toc.isEmpty()) return 0
    // 1. 标题完全相等
    toc.indexOfFirst { it.title == chapterTitle }.let { if (it >= 0) return it }
    // 2. 标题包含 (双向)
    if (chapterTitle.isNotEmpty()) {
        toc.indexOfFirst {
            it.title.contains(chapterTitle) || chapterTitle.contains(it.title)
        }.let { if (it >= 0) return it }
    }
    // 3. chapterIndex 兜底
    if (chapterIndex in toc.indices) return chapterIndex
    // 4. 末章
    return toc.lastIndex
}
