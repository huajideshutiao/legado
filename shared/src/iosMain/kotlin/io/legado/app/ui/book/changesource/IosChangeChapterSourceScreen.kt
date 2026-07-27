package io.legado.app.ui.book.changesource

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.primaryStr
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.booksource.IosBookSourceEditScreen
import io.legado.app.ui.compose.platform.rememberString
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * iOS 端章节换源 Screen (对照 app 端 ChangeChapterSourceDialog / desktop ChangeChapterSourceScreen)。
 *
 * # 复用 shared 实现
 *
 * 章节换源专属能力已下沉到 commonMain [ChangeBookSourceViewModelShared] (initData 6 参数重载 /
 * getToc / getContent); UI 组件复用 sharedUiMain [ChangeSourceTitleBar] / [SearchBookItem] /
 * [ChangeSourceRefreshBar] / [ChangeSourceBottomBar] / [ChapterTocPanel] / 各 MenuItem。
 *
 * # 与桌面端差异
 *
 * - 不重新注入 Provider (iOS 端由 [io.legado.app.MainViewController] 顶层注入 ThemeStore /
 *   AppConfig / EventBus / PreferenceStore, 本 Screen 在 [IosReaderScreen] 内渲染, 继承外层 Provider);
 * - platform 用 [IosChangeBookSourcePlatform] (Toasters 替代 println);
 * - onEdit 书源编辑入口暂 no-op (iOS 端 BookSourceEdit 路由未接入)。
 *
 * @param book 待换源书籍
 * @param chapterIndex 当前章节序号 (在新源目录中定位对应章节)
 * @param chapterTitle 当前章节标题 (标题栏显示 + 章节定位)
 * @param onBack 返回回调 (关闭覆盖层)
 * @param onReplaceContent 选中源+章节后取到的正文回调 → 宿主 saveText + 重载章节
 */
@Composable
fun IosChangeChapterSourceScreen(
    book: Book,
    chapterIndex: Int,
    chapterTitle: String,
    onBack: () -> Unit,
    onReplaceContent: (content: String) -> Unit,
) {
    // shared VM (KMP), remember 缓存避免重组时重建
    val scope = rememberCoroutineScope()
    val platform = remember { IosChangeBookSourcePlatform() }
    val viewModel = remember { ChangeBookSourceViewModelShared(scope, platform) }
    // 文案标签 (rememberString 是 @Composable, 顶层缓存后供菜单项 / 进度文本引用)
    val searchedCountProgressTemplate = rememberString("searched_count_progress")
    val bookSourceManageLabel = rememberString("book_source_manage")
    val checkAuthorLabel = rememberString("checkAuthor")
    val loadWordCountLabel = rememberString("load_word_count")
    val loadInfoLabel = rememberString("load_info")
    val loadTocLabel = rememberString("load_toc")
    val groupLabel = rememberString("group")

    // UI 状态
    var items by remember { mutableStateOf(emptyList<SearchBook>()) }
    var searching by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf(emptyList<String>()) }
    var searchMode by remember { mutableStateOf(false) }
    var screenKey by remember { mutableStateOf("") }
    var checkAuthor by remember { mutableStateOf(platform.changeSourceCheckAuthor) }
    var loadInfo by remember { mutableStateOf(platform.changeSourceLoadInfo) }
    var loadToc by remember { mutableStateOf(platform.changeSourceLoadToc) }
    var loadWordCount by remember { mutableStateOf(platform.changeSourceLoadWordCount) }
    val searchGroup = platform.searchGroup
    var durText by remember { mutableStateOf(book.originName) }
    val listState = rememberLazyListState()

    // toc 预览覆盖层状态
    var tocVisible by remember { mutableStateOf(false) }
    var tocLoading by remember { mutableStateOf(false) }
    var tocList by remember { mutableStateOf<List<BookChapter>?>(null) }
    var durChapterIndex by remember { mutableIntStateOf(0) }
    // 当前打开 toc 的源对应的 Book (clickChapter 时 getContent 用)
    var tocBook by remember { mutableStateOf<Book?>(null) }

    // 书源编辑覆盖层状态 (null=隐藏, 非空=显示; onEdit 触发后填入 searchBook.origin)
    var editSourceUrl by remember { mutableStateOf<String?>(null) }

    // 初始化数据 (用 shared.initData 6 参数重载, fromReadBookActivity=true 因从阅读页进入)
    LaunchedEffect(book.bookUrl) {
        viewModel.initData(book.name, book.author, true, book, chapterIndex, chapterTitle)
        viewModel.startSearch()
    }

    // 收集搜索结果
    LaunchedEffect(Unit) {
        viewModel.searchDataFlow.conflate().collect {
            items = it
            delay(1000)
        }
    }

    // 收集搜索状态
    LaunchedEffect(Unit) {
        viewModel.searchState.collect { searching = it }
    }

    // 收集换源进度
    LaunchedEffect(Unit) {
        viewModel.changeSourceProgress.drop(1).collect { (count, name) ->
            durText = searchedCountProgressTemplate.format(items.size, count, viewModel.totalSourceCount, name)
            delay(500)
        }
    }

    // 收集启用书源分组列表
    LaunchedEffect(Unit) {
        AppDbProviders.get().bookSourceDao.flowEnabledGroups().conflate().collect { groups = it }
    }

    // 首条变化回滚到顶
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
                // 返回键先收起 toc 再关闭
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
            // 溢出菜单
            TextMenuItem(bookSourceManageLabel) {
                dismiss()
                // TODO: 切到 iOS 端 BookSourceScreen 路由, 由宿主提供
            }
            CheckMenuItem(checkAuthorLabel, checkAuthor) {
                dismiss()
                checkAuthor = !checkAuthor
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
                groups = groups,
                selectedGroup = searchGroup,
                dismissParent = dismiss,
                onSelect = { group ->
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
                                // 选中源 → 打开 toc 预览覆盖层
                                val newBook = viewModel.bookMap[searchBook.primaryStr()] ?: searchBook.toBook()
                                tocBook = newBook
                                tocList = null
                                tocVisible = true
                                tocLoading = true
                                viewModel.getToc(
                                    book = newBook,
                                    onSuccess = { toc, _ ->
                                        durChapterIndex = findChapterIndex(toc, chapterIndex, chapterTitle)
                                        tocLoading = false
                                        tocList = toc
                                    },
                                    onError = { e ->
                                        tocVisible = false
                                        tocLoading = false
                                        AppLog.put("单章换源获取目录出错\n$e", e)
                                        Toasters.get().toast(e.localizedMessage ?: "加载目录失败")
                                    },
                                )
                            },
                            onTop = { viewModel.topSource(searchBook) },
                            onBottom = { viewModel.bottomSource(searchBook) },
                            onEdit = {
                                // 弹出书源编辑覆盖层 (对照 app 端 editSource: launch BookSourceEditActivity)
                                editSourceUrl = searchBook.origin
                            },
                            onDisable = { viewModel.disableSource(searchBook) },
                            onDelete = { viewModel.del(searchBook) },
                        )
                    }
                }
                ChangeSourceBottomBar(
                    durText = durText,
                    onDurClick = {
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
            // 章节目录预览覆盖层
            if (tocVisible) {
                ChapterTocPanel(
                    toc = tocList,
                    durChapterIndex = durChapterIndex,
                    loading = tocLoading,
                    onHide = { tocVisible = false },
                    onClickChapter = { bookChapter, nextChapterUrl ->
                        // 点击章节 → 取该源正文 → onReplaceContent 替换当前阅读页
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

    // 书源编辑覆盖层 (对照 app 端 editSourceResult: launch BookSourceEditActivity)
    // 保存后重新搜索全部源 (对照 app 端 viewModel.startSearch(), 章节换源用无参重搜)
    editSourceUrl?.let { url ->
        Dialog(
            onDismissRequest = { editSourceUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                IosBookSourceEditScreen(
                    sourceUrl = url,
                    onBack = { editSourceUrl = null },
                    onSaved = { _ ->
                        editSourceUrl = null
                        viewModel.startSearch()
                    },
                )
            }
        }
    }
}

/**
 * 章节定位 (对照 app 端 BookHelp.getDurChapter, 简化匹配策略与桌面端一致):
 * 1. 标题完全相等; 2. 标题包含 (双向); 3. chapterIndex 兜底; 4. 末章。
 *
 * 供 [IosChangeChapterSourceScreen] / [IosChangeBookSourceScreen] 复用。
 */
internal fun findChapterIndex(toc: List<BookChapter>, chapterIndex: Int, chapterTitle: String): Int {
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
