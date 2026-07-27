package io.legado.app.ui.book.changesource

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.primaryStr
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.booksource.IosBookSourceEditScreen
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.formatNative
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * iOS 端整书换源 Screen (对照 app 端 ChangeBookSourceDialog / desktop ChangeSourceScreen)。
 *
 * # 复用 shared 实现
 *
 * 章节换源专属能力已下沉到 commonMain [ChangeBookSourceViewModelShared] (initData 4 参数重载);
 * UI 组件复用 sharedUiMain [ChangeSourceTitleBar] / [SearchBookItem] /
 * [ChangeSourceRefreshBar] / [ChangeSourceBottomBar] / 各 MenuItem。
 *
 * # 与章节换源 ([IosChangeChapterSourceScreen]) 差异
 *
 * - initData 用 4 参数重载 (不传 chapterIndex / chapterTitle);
 * - 选中源后调 [onChangeSource] 回调 (source, newBook, toc), 由宿主执行迁移 +
 *   重载阅读视图 (对照 app 端 callBack.changeTo(source, book, toc));
 * - 无章节目录预览覆盖层 (ChapterTocPanel) 与单章正文替换 (getContent);
 * - 标题栏 title=书名, subtitle=作者 (章节换源 title=章节标题, subtitle=null);
 * - 溢出菜单多一项"刷新列表" (对照 app 端 ChangeBookSourceDialog 菜单 refreshList)。
 *
 * # 与桌面端差异
 *
 * - 不重新注入 Provider (iOS 端由 [io.legado.app.MainViewController] 顶层注入, 本 Screen
 *   在 [io.legado.app.ui.reader.IosReaderScreen] 内渲染, 继承外层 Provider);
 * - platform 用 [IosChangeBookSourcePlatform] (与章节换源共用同一实现);
 * - onEdit 书源编辑入口暂 no-op (iOS 端 BookSourceEdit 路由未接入)。
 *
 * @param book 待换源书籍 (用 name/author/bookUrl 匹配搜索结果)
 * @param onBack 返回回调 (关闭覆盖层)
 * @param onChangeSource 选中源回调 (source, newBook, toc) → 宿主执行迁移 + 重载阅读视图
 */
@Composable
fun IosChangeBookSourceScreen(
    book: Book,
    onBack: () -> Unit,
    onChangeSource: (BookSource, Book, List<BookChapter>) -> Unit,
) {
    // shared VM (KMP), remember 缓存避免重组时重建
    val scope = rememberCoroutineScope()
    val platform = remember { IosChangeBookSourcePlatform() }
    val viewModel = remember { ChangeBookSourceViewModelShared(scope, platform) }
    // 文案标签 (rememberString 是 @Composable, 顶层缓存后供菜单项 / 进度文本引用)
    val searchedCountProgressTemplate = rememberString("searched_count_progress")
    val bookSourceManageLabel = rememberString("book_source_manage")
    val refreshListLabel = rememberString("refresh_list")
    val checkAuthorLabel = rememberString("checkAuthor")
    val loadWordCountLabel = rememberString("load_word_count")
    val loadInfoLabel = rememberString("load_info")
    val loadTocLabel = rememberString("load_toc")
    val groupLabel = rememberString("group")
    val closeLabel = rememberString("close")
    val cancelLabel = rememberString("cancel")
    // 文案模板 (onError lambda 非 @Composable, 预先 remember)
    val changeSourceTocFailedTemplate = rememberString("change_source_toc_failed_log")
    val loadTocFailedText = rememberString("load_toc_failed")

    // UI 状态
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

    // 加载目录中等待对话框状态 (对照 app 端 waitDialog: getToc 时显示, 成功/失败/取消时隐藏)
    // null=隐藏, 非 null=显示 (字符串为待加载的书名, 用于显示在对话框文本中)
    var waitDialogBookName by remember { mutableStateOf<String?>(null) }
    // getToc 协程引用 (对照 app 端 waitDialog.onCancelListener = { coroutine.cancel() })
    var tocCoroutine by remember { mutableStateOf<Coroutine<*>?>(null) }

    // 书源编辑覆盖层状态 (null=隐藏, 非空=显示; onEdit 触发后填入 searchBook.origin)
    var editSourceUrl by remember { mutableStateOf<String?>(null) }

    // 初始化数据 (用 shared.initData 4 参数重载, fromReadBookActivity=true 因从阅读页进入)
    LaunchedEffect(book.bookUrl) {
        viewModel.initData(book.name, book.author, true, book)
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
            durText = searchedCountProgressTemplate.formatNative(items.size, count, viewModel.totalSourceCount, name)
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
            title = viewModel.name,
            subtitle = viewModel.author,
            searchMode = searchMode,
            screenKey = screenKey,
            searching = searching,
            onBack = onBack,
            onSearchModeChange = { searchMode = it },
            onScreen = { key ->
                screenKey = key
                viewModel.screen(key)
            },
            onStartStop = { viewModel.startOrStopSearch() },
        ) { dismiss ->
            // 溢出菜单 (对照 app 端 ChangeBookSourceDialog 菜单项)
            TextMenuItem(bookSourceManageLabel) {
                dismiss()
                // TODO: 切到 iOS 端 BookSourceScreen 路由, 由宿主提供
            }
            TextMenuItem(refreshListLabel) {
                dismiss()
                viewModel.startRefreshList()
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
                dismissParent = dismiss,
                onShowGroupPicker = { showGroupPicker = true },
            )
            TextMenuItem(closeLabel) {
                dismiss(); onBack()
            }
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
                                // 选中源 → 异步获取目录 → 通知宿主切换 → 返回
                                // 对照 app 端 ChangeBookSourceDialog.changeSource:
                                //   waitDialog.show + viewModel.getToc(book, onSuccess=changeTo, onError=AppLog.put)
                                if (searchBook.bookUrl != book.bookUrl) {
                                    val newBook = viewModel.bookMap[searchBook.primaryStr()] ?: searchBook.toBook()
                                    waitDialogBookName = searchBook.name
                                    tocCoroutine = viewModel.getToc(
                                        book = newBook,
                                        onSuccess = { toc, source ->
                                            waitDialogBookName = null
                                            tocCoroutine = null
                                            onChangeSource(source, newBook, toc)
                                            onBack()
                                        },
                                        onError = { e ->
                                            waitDialogBookName = null
                                            tocCoroutine = null
                                            AppLog.put(changeSourceTocFailedTemplate.formatNative(e), e)
                                            Toasters.get().toast(e.localizedMessage ?: loadTocFailedText)
                                        },
                                    )
                                }
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

    // 加载目录中等待对话框 (对照 app 端 waitDialog: getToc 时显示, 成功/失败/取消时隐藏)
    waitDialogBookName?.let { name ->
        AppAlertDialog(
            onDismissRequest = {
                tocCoroutine?.cancel()
                tocCoroutine = null
                waitDialogBookName = null
            },
            title = loadTocLabel,
            message = name,
            widthFraction = 0.8f,
            cancelButton = AlertButton(cancelLabel, dismissOnClick = false) {
                tocCoroutine?.cancel()
                tocCoroutine = null
                waitDialogBookName = null
            },
        )
    }

    // 书源编辑覆盖层 (对照 app 端 editSourceResult: launch BookSourceEditActivity)
    // 保存后用返回的 bookSourceUrl 单源重搜 (对照 app 端 viewModel.startSearch(origin))
    editSourceUrl?.let { url ->
        Dialog(
            onDismissRequest = { editSourceUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                IosBookSourceEditScreen(
                    sourceUrl = url,
                    onBack = { editSourceUrl = null },
                    onSaved = { origin ->
                        editSourceUrl = null
                        viewModel.startSearch(origin)
                    },
                )
            }
        }
    }
}
