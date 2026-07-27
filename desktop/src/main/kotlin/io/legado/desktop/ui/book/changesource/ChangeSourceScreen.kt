package io.legado.desktop.ui.book.changesource

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.primaryStr
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.changesource.ChangeBookSourceViewModelShared
import io.legado.app.ui.book.changesource.ChangeSourceBottomBar
import io.legado.app.ui.book.changesource.ChangeSourceRefreshBar
import io.legado.app.ui.book.changesource.ChangeSourceTitleBar
import io.legado.app.ui.book.changesource.CheckMenuItem
import io.legado.app.ui.book.changesource.GroupMenuItem
import io.legado.app.ui.book.changesource.SearchBookItem
import io.legado.app.ui.book.changesource.TextMenuItem
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 桌面端换源 Screen 入口 (对照 app 端 ChangeBookSourceDialog)。
 *
 * # KMP 化重构说明
 *
 * 原桌面端用 `mutableStateOf` 手动管理搜索结果 / 搜索状态 / 进度, 并自行实现
 * `searchBookSourcesFlow` / `topSource` / `bottomSource` / `disableSource` / `deleteSource`
 * 等私有函数 (与 app 端 ChangeBookSourceViewModel 逻辑重复)。
 *
 * 现复用 shared commonMain [ChangeBookSourceViewModelShared] (KMP 版), 通过
 * [DesktopChangeBookSourcePlatform] 注入桌面端简化实现:
 * - 搜索 / 排序 / 筛选 / 启停 / 进度: 全部走 shared VM, 与 app 端逻辑一致;
 * - 置顶 / 置底 / 禁用 / 删除: 转发到 shared VM (内部走 AppDbProviders / SourceHelp);
 * - 评分: 桌面端走 commonMain 的 SourceConfig (经 PreferenceProviders 持久化到 java.util.prefs);
 * - 字数加载: 桌面端默认关闭 (changeSourceLoadWordCount=false), 开启后 processContent
 *   直接返回原文 (ContentProcessor 未下沉);
 * - getDurChapter: 桌面端取末章 (BookHelp.getDurChapter 未下沉);
 * - toastOnUi: 用 println 替代。
 *
 * # 与 app 端差异
 *
 * - 桌面端 onChangeSource 回调传 (source, newBook, toc), 宿主拿到 toc 后自行调
 *   [io.legado.desktop.help.book.DesktopBookshelfManagePlatform.migrateBook] 完成迁移 +
 *   保存 DB (对照 app 端 Dialog.changeSource → callBack.changeTo(source, book, toc));
 * - 桌面端 WaitDialog 用 AlertDialog 显示"加载目录中..." (app 端用 MaterialProgressDialog);
 * - 桌面端无编辑书源入口 (BookSourceEditScreen 未下沉, onEdit no-op)。
 *
 * @param book 待换源书籍 (用 name/author/bookUrl/type 匹配搜索结果)
 * @param onBack 返回回调 (切回调用方路由)
 * @param onChangeSource 选中源回调 (source, newBook, toc) → 宿主执行 migrateBook + 保存 DB
 */
@Composable
fun ChangeSourceScreen(
    book: Book,
    onBack: () -> Unit,
    onChangeSource: (BookSource, Book, List<BookChapter>) -> Unit,
) {
    // 注入 desktop 平台 Provider (shared AppTheme 依赖)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            ChangeSourceContent(book = book, onBack = onBack, onChangeSource = onChangeSource)
        }
    }
}

@Composable
private fun ChangeSourceContent(
    book: Book,
    onBack: () -> Unit,
    onChangeSource: (BookSource, Book, List<BookChapter>) -> Unit,
) {
    // shared VM (KMP), remember 缓存避免重组时重建
    val scope = rememberCoroutineScope()
    val platform = remember { DesktopChangeBookSourcePlatform() }
    val viewModel = remember { ChangeBookSourceViewModelShared(scope, platform) }
    // 文案标签 (rememberString 是 @Composable, 顶层缓存后供菜单项 / LaunchedEffect 进度文本引用)
    val searchedCountProgressTemplate = rememberString("searched_count_progress")
    val bookSourceManageLabel = rememberString("book_source_manage")
    val refreshListLabel = rememberString("refresh_list")
    val checkAuthorLabel = rememberString("checkAuthor")
    val loadWordCountLabel = rememberString("load_word_count")
    val groupLabel = rememberString("group")
    val closeLabel = rememberString("close")

    // UI 状态 (对照 app 端 ChangeBookSourceDialog.Content 内的 remember 变量)
    var items by remember { mutableStateOf(emptyList<SearchBook>()) }
    var searching by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf(emptyList<String>()) }
    var searchMode by remember { mutableStateOf(false) }
    var screenKey by remember { mutableStateOf("") }
    var checkAuthor by remember { mutableStateOf(platform.changeSourceCheckAuthor) }
    val loadWordCount = platform.changeSourceLoadWordCount
    val searchGroup = platform.searchGroup
    var durText by remember { mutableStateOf(book.originName) }
    val listState = rememberLazyListState()

    // 加载目录中等待对话框状态 (对照 app 端 waitDialog: getToc 时显示, 成功/失败/取消时隐藏)
    // null=隐藏, 非 null=显示 (字符串为待加载的书名, 用于显示在对话框文本中)
    var waitDialogBookName by remember { mutableStateOf<String?>(null) }
    // getToc 协程引用 (对照 app 端 waitDialog.onCancelListener = { coroutine.cancel() })
    // 用户取消等待对话框时调 cancel() 终止后台 getToc 协程, 避免回调继续执行
    var tocCoroutine by remember { mutableStateOf<Coroutine<*>?>(null) }
    // 文案标签 (AlertDialog 用)
    val loadTocLabel = rememberString("load_toc")
    val cancelLabel = rememberString("cancel")

    // 初始化数据 (book 变化时重新初始化)
    // 用 shared.initData 6 参数重载显式接入 chapterIndex / chapterTitle 字段
    // (桌面端暂为普通换源, 传默认值 0 / ""; 字段已下沉到 commonMain,
    //  供未来桌面端章节换源场景使用, 与 app 端 ChangeChapterSourceViewModel 对齐)
    LaunchedEffect(book.bookUrl) {
        viewModel.initData(book.name, book.author, false, book, 0, "")
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

    // 收集启用书源分组列表 (供 GroupMenuItem 展示, 对照 app 端 flowEnabledGroups.collect)
    LaunchedEffect(Unit) {
        AppDbProviders.get().bookSourceDao.flowEnabledGroups().conflate().collect { groups = it }
    }

    // 首条变化回滚到顶 (对照 AdapterDataObserver: 首条插入/移动到 0 时 scrollToItem(0))
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
                // TODO: 切到桌面端 BookSourceScreen 路由, 由宿主提供
            }
            TextMenuItem(refreshListLabel) {
                dismiss()
                viewModel.startRefreshList()
            }
            CheckMenuItem(checkAuthorLabel, checkAuthor) {
                dismiss()
                checkAuthor = !checkAuthor
                // 桌面端 PreferenceProviders 已写回 (platform.changeSourceCheckAuthor 实时读)
                // 但 platform 是 remember 缓存, 写回后需重新读
                // 此处简化: 调 viewModel.refresh() 重新筛选
                viewModel.refresh()
            }
            CheckMenuItem(loadWordCountLabel, loadWordCount) {
                dismiss()
                // TODO: 字数加载依赖 ContentProcessor (未下沉), 桌面端暂不支持切换
                // viewModel.onLoadWordCountChecked(!loadWordCount)
            }
            GroupMenuItem(
                title = if (searchGroup.isEmpty()) groupLabel else "$groupLabel($searchGroup)",
                groups = groups,
                selectedGroup = searchGroup,
                dismissParent = dismiss,
                onSelect = { group ->
                    // TODO: 分组筛选需写回 platform.searchGroup 并重启搜索
                    // 桌面端 platform.searchGroup setter 已写回 PreferenceProviders
                    // 但 remember 缓存的 platform 实例需刷新, 暂不实现
                },
            )
            TextMenuItem(closeLabel) {
                dismiss(); onBack()
            }
        }
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
                            // 取 Book 实例: 优先用 bookMap (loadBookToc 缓存), 否则 toBook 转换 (对照 app 端)
                            val newBook = viewModel.bookMap[searchBook.primaryStr()] ?: searchBook.toBook()
                            // 显示"加载目录中"等待对话框 (对照 app 端 waitDialog.show)
                            waitDialogBookName = searchBook.name
                            // 异步获取目录 (对照 app 端 viewModel.getToc)
                            tocCoroutine = viewModel.getToc(
                                book = newBook,
                                onSuccess = { toc, source ->
                                    // 成功: 隐藏等待 + 通知宿主切换 + 返回
                                    // (对照 callBack.changeTo(source, book, toc) + dismiss)
                                    waitDialogBookName = null
                                    tocCoroutine = null
                                    onChangeSource(source, newBook, toc)
                                    onBack()
                                },
                                onError = { e ->
                                    // 失败: 隐藏等待 + AppLog.put + toast
                                    // (对照 app 端 waitDialog.dismissSafe + AppLog.put + toastOnUi(true))
                                    waitDialogBookName = null
                                    tocCoroutine = null
                                    AppLog.put("换源获取目录出错\n$e", e)
                                    Toasters.get().toast(e.localizedMessage ?: "加载目录失败")
                                },
                            )
                        }
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

    // 加载目录中等待对话框 (对照 app 端 waitDialog: getToc 时显示, 成功/失败/取消时隐藏)
    // 用户点击外部或取消按钮: cancel 协程 + 隐藏对话框
    // (对照 app 端 waitDialog.onCancelListener = { coroutine.cancel() })
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
}
