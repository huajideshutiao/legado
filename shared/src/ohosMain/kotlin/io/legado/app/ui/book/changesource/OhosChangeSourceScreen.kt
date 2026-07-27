package io.legado.app.ui.book.changesource

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.BookHelpChapterLocator
import io.legado.app.help.book.primaryStr
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.rememberString
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 鸿蒙端整书换源 Screen 入口 (对照 iOS 端 [IosChangeBookSourceScreen])。
 *
 * # 复用 shared 实现
 *
 * 章节换源专属能力已下沉到 commonMain [ChangeBookSourceViewModelShared] (initData 4 参数重载);
 * UI 组件复用 sharedUiMain [ChangeSourceTitleBar] / [SearchBookItem] /
 * [ChangeSourceRefreshBar] / [ChangeSourceBottomBar] / 各 MenuItem。
 *
 * # 与章节换源 ([OhosChangeChapterSourceScreen]) 差异
 *
 * - initData 用 4 参数重载 (不传 chapterIndex / chapterTitle);
 * - 选中源后调 [onChangeSource] 回调 (source, newBook, toc), 由宿主执行迁移 +
 *   重载阅读视图 (对照 app 端 callBack.changeTo(source, book, toc));
 * - 无章节目录预览覆盖层 (ChapterTocPanel) 与单章正文替换 (getContent);
 * - 标题栏 title=书名, subtitle=作者 (章节换源 title=章节标题, subtitle=null);
 * - 溢出菜单多一项"刷新列表" (对照 app 端 ChangeBookSourceDialog 菜单 refreshList)。
 *
 * @param book 待换源书籍 (用 name/author/bookUrl 匹配搜索结果)
 * @param onBack 返回回调 (关闭覆盖层)
 * @param onChangeSource 选中源回调 (source, newBook, toc) → 宿主执行迁移 + 重载阅读视图
 */
@Composable
fun OhosChangeBookSourceScreen(
    book: Book,
    onBack: () -> Unit,
    onChangeSource: (BookSource, Book, List<BookChapter>) -> Unit,
) {
    // shared VM (KMP), remember 缓存避免重组时重建
    val scope = rememberCoroutineScope()
    val platform = remember { OhosChangeBookSourcePlatform() }
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
    // KP-ohos: 鸿蒙端 BookSourceEdit 路由未接入, onEdit 暂 no-op
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
                // TODO: 切到鸿蒙端 BookSourceScreen 路由, 由宿主提供
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
                                            AppLog.put(String.format(changeSourceTocFailedTemplate, e), e)
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

    // 加载目录中等待对话框 (对照 app 端 waitDialog: getToc 时显示, 成功/失败/取消时隐藏)
    waitDialogBookName?.let { name ->
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = {
                tocCoroutine?.cancel()
                tocCoroutine = null
                waitDialogBookName = null
            },
            title = { Text(loadTocLabel) },
            text = { Text(name) },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    tocCoroutine?.cancel()
                    tocCoroutine = null
                    waitDialogBookName = null
                }) { Text(cancelLabel) }
            },
        )
    }

    // 书源编辑覆盖层 (KP-ohos: 鸿蒙端 BookSourceEdit 路由未接入, 暂用 stub Dialog)
    // 后续接入 OhosBookSourceEditScreen 后替换
    editSourceUrl?.let { _ ->
        Dialog(
            onDismissRequest = { editSourceUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                // TODO: 接入 OhosBookSourceEditScreen 后替换此 stub
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * 鸿蒙端章节换源 Screen 入口 (对照 iOS 端 [IosChangeChapterSourceScreen])。
 *
 * # 复用 shared 实现
 *
 * 章节换源专属能力已下沉到 commonMain [ChangeBookSourceViewModelShared] (initData 6 参数重载 /
 * getToc / getContent); UI 组件复用 sharedUiMain [ChangeSourceTitleBar] / [SearchBookItem] /
 * [ChangeSourceRefreshBar] / [ChangeSourceBottomBar] / [ChapterTocPanel] / 各 MenuItem。
 *
 * @param book 待换源书籍
 * @param chapterIndex 当前章节序号 (在新源目录中定位对应章节)
 * @param chapterTitle 当前章节标题 (标题栏显示 + 章节定位)
 * @param onBack 返回回调 (关闭覆盖层)
 * @param onReplaceContent 选中源+章节后取到的正文回调 → 宿主 saveText + 重载章节
 */
@Composable
fun OhosChangeChapterSourceScreen(
    book: Book,
    chapterIndex: Int,
    chapterTitle: String,
    onBack: () -> Unit,
    onReplaceContent: (content: String) -> Unit,
) {
    // shared VM (KMP), remember 缓存避免重组时重建
    val scope = rememberCoroutineScope()
    val platform = remember { OhosChangeBookSourcePlatform() }
    val viewModel = remember { ChangeBookSourceViewModelShared(scope, platform) }
    // 文案标签 (rememberString 是 @Composable, 顶层缓存后供菜单项 / 进度文本引用)
    val searchedCountProgressTemplate = rememberString("searched_count_progress")
    val bookSourceManageLabel = rememberString("book_source_manage")
    val checkAuthorLabel = rememberString("checkAuthor")
    val loadWordCountLabel = rememberString("load_word_count")
    val loadInfoLabel = rememberString("load_info")
    val loadTocLabel = rememberString("load_toc")
    val groupLabel = rememberString("group")
    // 文案模板 (onError lambda 非 @Composable, 预先 remember)
    val changeChapterSourceTocFailedTemplate = rememberString("change_chapter_source_toc_failed_log")
    val loadTocFailedText = rememberString("load_toc_failed")

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
    // KP-ohos: 鸿蒙端 BookSourceEdit 路由未接入, onEdit 暂 no-op
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
                // TODO: 切到鸿蒙端 BookSourceScreen 路由, 由宿主提供
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
                                        AppLog.put(String.format(changeChapterSourceTocFailedTemplate, e), e)
                                        Toasters.get().toast(e.localizedMessage ?: loadTocFailedText)
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

    // 书源编辑覆盖层 (KP-ohos: 鸿蒙端 BookSourceEdit 路由未接入, 暂用 stub Dialog)
    // 后续接入 OhosBookSourceEditScreen 后替换
    editSourceUrl?.let { _ ->
        Dialog(
            onDismissRequest = { editSourceUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                // TODO: 接入 OhosBookSourceEditScreen 后替换此 stub
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * 鸿蒙端 [ChangeBookSourcePlatform] 实现。
 *
 * 对照 iOS 端 [IosChangeBookSourcePlatform] / 桌面端 DesktopChangeBookSourcePlatform,
 * 行为对齐, 仅 toast 通道替换为鸿蒙端 [Toasters]。
 *
 * # 简化项 (与 iOS/桌面端一致, 依赖未下沉的 app 端组件)
 *
 * - **getDurChapter**: 走 [BookHelpChapterLocator.getDurChapter] (已下沉 commonMain, 与 app 端同算法);
 * - **processContent**: 直接返回 content (ContentProcessor 依赖未下沉, 但默认配置不触发此方法);
 * - **toastOnUi**: 用 [Toasters.get().toast] (鸿蒙端已注册 OhosToaster)。
 *
 * PreferenceProviders / SourceConfig 已下沉 commonMain, 鸿蒙端在
 * [io.legado.app.help.config.OhosProviderRegistry.registerOhosProviders] 中已注册
 * OhosPreferenceProvider, 直接读写持久化配置。
 */
class OhosChangeBookSourcePlatform : ChangeBookSourcePlatform {

    private val prefs get() = PreferenceProviders.get()

    // ---- AppConfig 相关 ----

    override val threadCount: Int
        get() = prefs.getInt(PreferKey.threadCount, 16)

    override var searchGroup: String
        get() = prefs.getString(PreferKey.searchGroup, "")
        set(value) {
            prefs.putString(PreferKey.searchGroup, value)
        }

    override var changeSourceCheckAuthor: Boolean
        get() = prefs.getBoolean(PreferKey.changeSourceCheckAuthor, true)
        set(value) {
            prefs.putBoolean(PreferKey.changeSourceCheckAuthor, value)
        }

    override var changeSourceLoadInfo: Boolean
        get() = prefs.getBoolean(PreferKey.changeSourceLoadInfo, false)
        set(value) {
            prefs.putBoolean(PreferKey.changeSourceLoadInfo, value)
        }

    override var changeSourceLoadToc: Boolean
        get() = prefs.getBoolean(PreferKey.changeSourceLoadToc, false)
        set(value) {
            prefs.putBoolean(PreferKey.changeSourceLoadToc, value)
        }

    override var changeSourceLoadWordCount: Boolean
        get() = prefs.getBoolean(PreferKey.changeSourceLoadWordCount, false)
        set(value) {
            prefs.putBoolean(PreferKey.changeSourceLoadWordCount, value)
        }

    // ---- BookHelp 相关 ----

    // 委托 BookHelpChapterLocator (已下沉 commonMain), 与 app 端同一份章节名相似度匹配算法
    override fun getDurChapter(oldBook: Book, chapters: List<BookChapter>): Int {
        return BookHelpChapterLocator.getDurChapter(oldBook, chapters)
    }

    // ---- ContentProcessor 相关 ----

    // 鸿蒙端简化: 直接返回 content (与 iOS/桌面端一致, ContentProcessor 未下沉)
    override fun processContent(
        oldBook: Book, chapter: BookChapter, content: String, includeTitle: Boolean
    ): CharSequence {
        return content
    }

    // ---- SourceConfig 评分相关 ----

    override fun setBookScore(origin: String, name: String, author: String, score: Int) {
        SourceConfig.setBookScore(origin, name, author, score)
    }

    override fun getBookScore(origin: String, name: String, author: String): Int {
        return SourceConfig.getBookScore(origin, name, author)
    }

    override fun getSourceScore(origin: String): Int {
        return SourceConfig.getSourceScore(origin)
    }

    // ---- Toast 相关 ----

    // 鸿蒙端用 Toasters (OhosProviderRegistry 已注册 OhosToaster, 对照桌面端 println)
    override fun toastOnUi(msg: String) {
        Toasters.get().toast(msg)
    }
}

/**
 * 章节定位 (对照 app 端 BookHelp.getDurChapter, 简化匹配策略与 iOS/桌面端一致):
 * 1. 标题完全相等; 2. 标题包含 (双向); 3. chapterIndex 兜底; 4. 末章。
 *
 * 供 [OhosChangeChapterSourceScreen] / [OhosChangeBookSourceScreen] 复用。
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
