package io.legado.desktop.ui.book.toc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.toc.TocScreen as SharedTocScreen
import io.legado.app.ui.book.toc.TocScrollCmd
import io.legado.app.ui.book.toc.rule.TxtTocRuleEditDialog
import io.legado.app.ui.book.toc.TocUiActions
import io.legado.app.ui.book.toc.TocUiState
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端目录页 Screen 入口 (包装 shared/sharedUiMain 的 [SharedTocScreen])。
 *
 * # 职责
 *
 * 对照 desktop [io.legado.desktop.ui.bookinfo.BookInfoScreen] 模式, 仅做桌面平台适配,
 * 业务展示与交互逻辑全部下沉到 shared/sharedUiMain 的 [SharedTocScreen]:
 *
 * - **数据加载**: [LaunchedEffect] 异步查 [AppDbProviders.get].bookDao.getBook(bookUrl)
 *   加载本地完整 Book (含 durChapterIndex 等); 再异步加载章节列表 + 书签列表
 * - **UI state**: 构造 [TocUiState], 桌面端简化项:
 *   - `displayTitleMap` 留空 (净标题异步计算依赖 ContentProcessor, 桌面端未下沉)
 *   - `cacheFileNames` 留空 (依赖 BookHelp.getChapterFiles, 桌面端 BookHelpAccessor 未暴露)
 *   - `useReplace` 默认 false (PreferKey.tocUiUseReplace 未在 AppConfigAccessor 暴露)
 *   - `countWords` 初始化自 [AppConfigProviders.get].tocCountWords
 * - **actions**: 实现 [TocUiActions] 15 个方法, 核心动作 (onBack/openChapter/openBookmark/
 *   setSearchMode/setQuery/toggleVolume/reverseChapterList/toggleUseReplace/toggleCountWords)
 *   接入真实逻辑, 其余暂为 no-op + TODO 注释 (依赖未下沉 Dialog/文件选择器)
 * - **路由**: [onBack] / [onChapterClick] 由 [io.legado.desktop.ui.DesktopApp] 注入,
 *   章节点击 → 切到 READER 路由 (携带 Book + chapterIndex)
 *
 * # 简化项
 *
 * - 不接入 IntentData.chapterList 内存章节表 (桌面端无 Intent 传递)
 * - 不实现净标题异步计算 (displayTitleMap 留空, 显示原始 title)
 * - 不接入 BookHelp.getChapterFiles 缓存文件名检测 (cacheFileNames 留空, 未缓存图标统一不显示)
 * - 不持久化 useReplace/countWords 切换 (TODO: 写回 PreferKey)
 * - exportBookmark/exportBookmarkMd 接入 FileDialog SAVE + bookmarkDao.getByBook (JSON / Markdown 导出)
 * - editBookmark 接入 shared/sharedUiMain 下沉的 BookmarkDialog (onConfirm/onDelete 落库 + 重载)
 * - onChapterLongClick 接入 Toasters.toastLong (对照 TocActivity.longToastOnUi)
 *
 * # Provider 注入
 *
 * 参考 [io.legado.desktop.ui.booksource.BookSourceScreen] 模式, 本 Screen 自带
 * CompositionLocalProvider + [AppTheme] 包裹, 注入桌面端 ThemeStore/AppConfig/EventBus,
 * 让 shared/sharedUiMain 的 [SharedTocScreen] 内部调 AppTheme.colors 等可正常工作。
 *
 * @param book 目录页目标书籍 (由 DesktopApp 注入, 内部用 bookUrl 从 DAO 加载完整 Book)
 * @param onBack 返回回调 (切回调用方路由: 详情/书架)
 * @param onChapterClick 章节点击回调 (切到 READER 路由, 参数为章节 index)
 */
@Composable
fun TocScreen(
    book: Book,
    onBack: () -> Unit,
    onChapterClick: (Int) -> Unit,
) {
    // 注入 desktop 平台 Provider (commonMain AppTheme 依赖)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            TocScreenContent(book = book, onBack = onBack, onChapterClick = onChapterClick)
        }
    }
}

/**
 * 目录页内容主体 (Provider + AppTheme 内部)。
 *
 * 持有 13 个状态字段 (对照 app 端 [TocActivity] 的 var 字段), 在 [LaunchedEffect] 内
 * 异步加载, 并构造 [TocUiState] + [DesktopTocActions] 调用 [SharedTocScreen]。
 */
@Composable
private fun TocScreenContent(
    book: Book,
    onBack: () -> Unit,
    onChapterClick: (Int) -> Unit,
) {
    // ===== 状态字段 (对照 TocActivity 同名字段) =====
    val bookState = remember { mutableStateOf<Book?>(null) }
    var loadedBook by bookState
    var durChapterIndex by remember { mutableStateOf(0) }
    val searchingState = remember { mutableStateOf(false) }
    var searching by searchingState
    val searchKeyState = remember { mutableStateOf("") }
    var searchKey by searchKeyState
    val chaptersState = remember { mutableStateOf<List<BookChapter>>(emptyList()) }
    var chapters by chaptersState
    val collapsedVolumesState = remember { mutableStateOf<Set<Int>>(emptySet()) }
    var collapsedVolumes by collapsedVolumesState
    // 桌面端简化: 不接入 BookHelp.getChapterFiles, cacheFileNames 留空
    val cacheFileNamesState = remember { mutableStateOf<Set<String>>(emptySet()) }
    // useReplace 默认 false (PreferKey.tocUiUseReplace 未在 AppConfigAccessor 暴露)
    val useReplaceState = remember { mutableStateOf(false) }
    var useReplace by useReplaceState
    // countWords 初始化自 AppConfigProviders (桌面端 DesktopAppConfigAccessor 已实现)
    val countWordsState = remember { mutableStateOf(AppConfigProviders.get().tocCountWords) }
    var countWords by countWordsState
    val chapterScrollState = remember { mutableStateOf(TocScrollCmd()) }
    var chapterScroll by chapterScrollState
    val bookmarksState = remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var bookmarks by bookmarksState
    val bookmarkScrollState = remember { mutableStateOf(TocScrollCmd()) }
    var bookmarkScroll by bookmarkScrollState

    val scope = rememberCoroutineScope()
    // 应用日志对话框状态 (false=隐藏, true=显示; showLog 触发, 末尾 AppLogDialog 渲染)
    var showLogDialog by remember { mutableStateOf(false) }

    // TxtTocRuleEditDialog 显隐状态 (接入 DesktopTocActions.showTocRegexDialog 回调):
    // false=隐藏, true=显示 (rule=null 新增, onConfirm 调 txtTocRuleDao.insert)
    var showTocRegexDialog by remember { mutableStateOf(false) }

    // BookmarkDialog 显隐状态 + 当前编辑书签 (editBookmark 触发, 接入 shared 下沉的 BookmarkDialog):
    // onConfirm 调 bookmarkDao.update, onDelete 调 bookmarkDao.delete, 落库后重载书签列表
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }

    // ===== 数据加载 (对照 TocActivity.onActivityCreated + viewModel.bookData.observe) =====
    LaunchedEffect(book.bookUrl) {
        // 优先用 DAO 加载本地完整 Book (含 durChapterIndex 等), 失败回退入参 book
        val b = AppDbProviders.get().bookDao.getBook(book.bookUrl) ?: book
        loadedBook = b
        durChapterIndex = b.durChapterIndex
        // 加载章节列表 + 书签 (对照 TocActivity.upChapterList + upBookmarks)
        // 失败时 AppLog 记录, 不中断 UI (空列表 fallback)
        runCatching { loadChapterList(b, "") }
            .onSuccess { setChapterListInternal(it, bookState, chaptersState, collapsedVolumesState, chapterScrollState) }
            .onFailure { AppLog.put("目录界面加载章节失败\n${it.localizedMessage}", it) }
        runCatching { loadBookmarks(b, "") }
            .onSuccess { setBookmarksInternal(it, bookState, bookmarksState, bookmarkScrollState) }
            .onFailure { AppLog.put("目录界面获取书签数据失败\n${it.localizedMessage}", it) }
    }

    // ===== actions =====
    val actions = remember(onBack, onChapterClick, scope) {
        DesktopTocActions(
            bookState = bookState,
            searchingState = searchingState,
            searchKeyState = searchKeyState,
            chaptersState = chaptersState,
            collapsedVolumesState = collapsedVolumesState,
            chapterScrollState = chapterScrollState,
            bookmarksState = bookmarksState,
            bookmarkScrollState = bookmarkScrollState,
            useReplaceState = useReplaceState,
            countWordsState = countWordsState,
            scope = scope,
            onBack = onBack,
            onChapterClick = onChapterClick,
            onShowTocRegexDialog = { showTocRegexDialog = true },
            onShowLogCb = { showLogDialog = true },
            onEditBookmarkCb = { bookmark ->
                editingBookmark = bookmark
                showBookmarkDialog = true
            },
        )
    }

    // ===== state + 调用 shared Screen =====
    val state = TocUiState(
        book = loadedBook,
        durChapterIndex = durChapterIndex,
        searching = searching,
        searchKey = searchKey,
        chapters = chapters,
        collapsedVolumes = collapsedVolumes,
        // 桌面端简化: 不接入净标题异步计算, 留空 (ChapterItem 内 fallback 到 item.title)
        displayTitleMap = emptyMap(),
        cacheFileNames = cacheFileNamesState.value,
        useReplace = useReplace,
        countWords = countWords,
        chapterScroll = chapterScroll,
        bookmarks = bookmarks,
        bookmarkScroll = bookmarkScroll,
        isLocalBook = loadedBook?.isLocal == true,
    )

    // 位置参数调用 (shared Screen 函数类型参数不能用命名参数, 见任务说明)
    SharedTocScreen(state, actions)

    // ---- TXT 目录规则编辑对话框 (showTocRegexDialog 触发, TxtTocRuleEditDialog(rule=null) 新增) ----
    // 剪贴板桥接用 AWT Toolkit (替代 app 端 getClipText/sendToClip):
    // - clipTextProvider: 读系统剪贴板文本 (供"粘贴规则"菜单项用)
    // - clipTextSink: 写系统剪贴板文本 (供"复制规则"菜单项用)
    // onConfirm: 调 txtTocRuleDao.insert(r) 落库 (AppDbProviders.get().txtTocRuleDao 已下沉)
    if (showTocRegexDialog) {
        TxtTocRuleEditDialog(
            rule = null,
            onConfirm = { r ->
                scope.launch { AppDbProviders.get().txtTocRuleDao.insert(r) }
            },
            onDismiss = { showTocRegexDialog = false },
            clipTextProvider = {
                runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
                }.getOrNull()
            },
            clipTextSink = { text ->
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            },
        )
    }
    // ---- 应用日志对话框 (showLog 触发, 调用 shared/sharedUiMain 下沉的 AppLogDialog) ----
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    // ---- 书签编辑对话框 (editBookmark 触发, 调用 shared/sharedUiMain 下沉的 BookmarkDialog) ----
    // onConfirm: 调 bookmarkDao.update 落库 + 重载书签列表 (对照 app 端 BookmarkDialog.onConfirm → insert)
    // onDelete: 调 bookmarkDao.delete 删除 + 重载书签列表 (对照 app 端 BookmarkDialog.delete)
    // 注: app 端用 insert (Room upsert), desktop 端 editBookmark 仅编辑已有书签, 用 update 语义更精确
    if (showBookmarkDialog) {
        editingBookmark?.let { bookmark ->
            BookmarkDialog(
                bookmark = bookmark,
                showDelete = true,
                onConfirm = { updated ->
                    scope.launch {
                        AppDatabaseProviders.get().appDb.bookmarkDao.update(updated)
                        reloadBookmarks(loadedBook, searchKey, bookState, bookmarksState, bookmarkScrollState)
                    }
                    showBookmarkDialog = false
                },
                onDismiss = { showBookmarkDialog = false },
                onDelete = {
                    scope.launch {
                        AppDatabaseProviders.get().appDb.bookmarkDao.delete(bookmark)
                        reloadBookmarks(loadedBook, searchKey, bookState, bookmarksState, bookmarkScrollState)
                    }
                    showBookmarkDialog = false
                },
            )
        }
    }
}

// ===== 辅助加载函数 (LaunchedEffect 内调用, suspend) =====

/**
 * 加载章节列表 (对照 TocActivity.upChapterList)。
 *
 * - `key` 空白: 取 [BookChapterDao.getChapterList] 全部章节
 * - `key` 非空: 取 [BookChapterDao.search] 过滤章节
 *
 * 加载完成后调 [setChapterListInternal] 更新 chapters + 重置 collapsedVolumes +
 * 计算定位 scrollPos (当前章前一条)。
 */
private suspend fun loadChapterList(book: Book, key: String): List<BookChapter> {
    val dao = AppDbProviders.get().bookChapterDao
    val end = (book.simulatedTotalChapterNum() - 1).coerceAtLeast(0)
    return if (key.isBlank()) {
        dao.getChapterList(book.bookUrl, 0, end)
    } else {
        dao.search(book.bookUrl, key, 0, end)
    }
}

/**
 * 加载书签列表 (对照 TocActivity.upBookmarks)。
 *
 * - `key` 空白: 取 [BookmarkDao.getByBook] (suspend 一次性查询)
 * - `key` 非空: 取 [BookmarkDao.flowSearch].first() (Flow 首帧, 与 TocActivity collect 首次语义对齐)
 */
private suspend fun loadBookmarks(book: Book, key: String): List<Bookmark> {
    // bookmarkDao 未在 AppDbAccessor 暴露, 走完整 AppDatabase 实例 (参照 BookshelfManageScreen 的 bookGroupDao)
    val dao = AppDatabaseProviders.get().appDb.bookmarkDao
    return if (key.isBlank()) {
        dao.getByBook(book.name, book.author)
    } else {
        dao.flowSearch(book.name, book.author, key).first()
    }
}

/**
 * 写入章节列表 + 重置折叠 + 计算定位 (对照 TocActivity.setChapterList)。
 *
 * 定位规则: 找到第一个 index >= durChapterIndex 的章节前一条 (前一条避免标题被顶栏遮挡)。
 *
 * 文件级函数供 [TocScreenContent] 的 LaunchedEffect 与 [DesktopTocActions] 的
 * setQuery/reverseChapterList 共用, 避免逻辑重复。
 */
private fun setChapterListInternal(
    list: List<BookChapter>,
    bookState: MutableState<Book?>,
    chaptersState: MutableState<List<BookChapter>>,
    collapsedVolumesState: MutableState<Set<Int>>,
    chapterScrollState: MutableState<TocScrollCmd>,
) {
    collapsedVolumesState.value = emptySet()
    chaptersState.value = list
    val dur = bookState.value?.durChapterIndex ?: 0
    var scrollPos = 0
    for ((position, chapter) in list.withIndex()) {
        if (chapter.index >= dur) break
        scrollPos = position
    }
    chapterScrollState.value = TocScrollCmd(scrollPos, chapterScrollState.value.tick + 1)
}

/**
 * 写入书签列表 + 计算定位 (对照 TocActivity.upBookmarks collect 内逻辑)。
 */
private fun setBookmarksInternal(
    list: List<Bookmark>,
    bookState: MutableState<Book?>,
    bookmarksState: MutableState<List<Bookmark>>,
    bookmarkScrollState: MutableState<TocScrollCmd>,
) {
    bookmarksState.value = list
    val dur = bookState.value?.durChapterIndex ?: 0
    var scrollPos = 0
    for ((position, bookmark) in list.withIndex()) {
        if (bookmark.chapterIndex >= dur) break
        scrollPos = position
    }
    bookmarkScrollState.value = TocScrollCmd(scrollPos, bookmarkScrollState.value.tick + 1)
}

/**
 * 重载书签列表 (BookmarkDialog onConfirm/onDelete 后调用)。
 *
 * 复用 [loadBookmarks] + [setBookmarksInternal], 按当前 searchKey 重新查询并写入状态,
 * 保证编辑/删除后 UI 即时刷新 (桌面端无 Flow 观察, 需手动重载)。
 */
private suspend fun reloadBookmarks(
    book: Book?,
    searchKey: String,
    bookState: MutableState<Book?>,
    bookmarksState: MutableState<List<Bookmark>>,
    bookmarkScrollState: MutableState<TocScrollCmd>,
) {
    val b = book ?: return
    runCatching { loadBookmarks(b, searchKey) }
        .onSuccess { setBookmarksInternal(it, bookState, bookmarksState, bookmarkScrollState) }
        .onFailure { AppLog.put("目录界面重载书签失败\n${it.localizedMessage}", it) }
}

/**
 * 桌面端 [TocUiActions] 实现。
 *
 * 15 个回调中:
 * - 真实实现: [onBack] / [setSearchMode] / [setQuery] / [toggleVolume] / [openChapter] /
 *   [reverseChapterList] / [toggleUseReplace] / [toggleCountWords] / [openBookmark] /
 *   [showTocRegexDialog] / [showLog] / [editBookmark] / [onChapterLongClick] /
 *   [exportBookmark] / [exportBookmarkMd]
 * - no-op + TODO: [toggleSplitLongChapter]
 *
 * 持有各状态 [MutableState] 引用 (Compose 局部 mutableStateOf 的稳定引用),
 * 回调内读写 `.value` 即时触发重组。
 *
 * @param bookState 当前 Book (null 时 setQuery 等动作直接返回)
 * @param searchingState 搜索模式开关
 * @param searchKeyState 搜索关键词
 * @param chaptersState 章节列表
 * @param collapsedVolumesState 卷折叠状态
 * @param chapterScrollState 章节列表定位命令 (tick 递增驱动 LaunchedEffect 滚动)
 * @param bookmarksState 书签列表
 * @param bookmarkScrollState 书签列表定位命令
 * @param useReplaceState 净化替换开关 (桌面端简化: 仅内存切换, TODO 持久化)
 * @param countWordsState 字数显示开关 (桌面端简化: 仅内存切换, TODO 持久化)
 * @param scope 协程作用域 (setQuery 异步重载章节/书签)
 * @param onBack 由 DesktopApp 注入的返回回调
 * @param onChapterClick 由 DesktopApp 注入的章节点击回调 (携带 index 切到 READER)
 */
private class DesktopTocActions(
    private val bookState: MutableState<Book?>,
    private val searchingState: MutableState<Boolean>,
    private val searchKeyState: MutableState<String>,
    private val chaptersState: MutableState<List<BookChapter>>,
    private val collapsedVolumesState: MutableState<Set<Int>>,
    private val chapterScrollState: MutableState<TocScrollCmd>,
    private val bookmarksState: MutableState<List<Bookmark>>,
    private val bookmarkScrollState: MutableState<TocScrollCmd>,
    private val useReplaceState: MutableState<Boolean>,
    private val countWordsState: MutableState<Boolean>,
    private val scope: CoroutineScope,
    private val onBack: () -> Unit,
    private val onChapterClick: (Int) -> Unit,
    private val onShowTocRegexDialog: () -> Unit,
    private val onShowLogCb: () -> Unit,
    private val onEditBookmarkCb: (Bookmark) -> Unit,
) : TocUiActions {

    override fun onBack() = onBack.invoke()

    override fun setSearchMode(active: Boolean) {
        searchingState.value = active
        // 关闭搜索模式时清空关键词 (对照 TocActivity.setSearchMode)
        if (!active && searchKeyState.value.isNotEmpty()) {
            setQuery("")
        }
    }

    override fun setQuery(query: String) {
        searchKeyState.value = query
        // 关键词变化时重新加载章节 + 书签 (对照 TocActivity.setQuery → upChapterList + upBookmarks)
        scope.launch {
            val book = bookState.value ?: return@launch
            runCatching {
                loadChapterList(book, query)
            }.onSuccess { list ->
                setChapterListInternal(list, bookState, chaptersState, collapsedVolumesState, chapterScrollState)
            }.onFailure {
                AppLog.put("目录界面加载章节失败\n${it.localizedMessage}", it)
            }
            runCatching {
                loadBookmarks(book, query)
            }.onSuccess { list ->
                setBookmarksInternal(list, bookState, bookmarksState, bookmarkScrollState)
            }.onFailure {
                AppLog.put("目录界面获取书签数据失败\n${it.localizedMessage}", it)
            }
        }
    }

    override fun toggleVolume(volume: BookChapter) {
        // 卷折叠: 切换卷 index 在 collapsedVolumes 集合中的存在性 (对照 TocActivity.toggleVolume)
        val current = collapsedVolumesState.value
        collapsedVolumesState.value = if (volume.index in current) {
            current - volume.index
        } else {
            current + volume.index
        }
    }

    override fun openChapter(chapter: BookChapter) {
        // 章节点击 → 调 onChapterClick 回调切到阅读页 (对照 TocActivity.openChapter setResult + finish)
        onChapterClick.invoke(chapter.index)
    }

    override fun reverseChapterList() {
        // 反转章节列表 + 重新计算 index (对照 TocActivity.reverseChapterList)
        val current = chaptersState.value
        if (current.isEmpty()) return
        val reversed = current.reversed().apply {
            forEachIndexed { i, c -> c.index = i }
        }
        setChapterListInternal(reversed, bookState, chaptersState, collapsedVolumesState, chapterScrollState)
        // TODO: 持久化反转 (调 viewModel.reverseToc → bookChapterDao.insert + book.config.reverseToc)
        //   桌面端 BookHelpAccessor 未暴露 reverseToc 编排, 暂仅内存反转
    }

    override fun toggleUseReplace() {
        // 桌面端简化: 仅切换内存状态 (TODO: 写回 PreferKey.tocUiUseReplace + 重算 displayTitleMap)
        useReplaceState.value = !useReplaceState.value
    }

    override fun toggleCountWords() {
        // 桌面端简化: 仅切换内存状态 (TODO: 写回 PreferKey.tocCountWords)
        countWordsState.value = !countWordsState.value
    }

    override fun toggleSplitLongChapter() {
        // TODO: 修改 Book.config.splitLongChapter + 重载章节列表, 依赖 BookHelpProviders + FileBook
    }

    override fun showTocRegexDialog() {
        // 触发 TxtTocRuleEditDialog 显示 (外层 Composable 控制 showTocRegexDialog 状态,
        // rule=null 新增, onConfirm 调 txtTocRuleDao.insert, 替代原 no-op TODO)
        onShowTocRegexDialog.invoke()
    }

    override fun exportBookmark() {
        // 导出当前书书签为 JSON: FileDialog SAVE → bookmarkDao.getByBook → GSON.toJson → 写文件
        // 对照 app 端 TocViewModel.saveBookmark (SAF 选目录 + GSON.writeText)
        scope.launch {
            val book = bookState.value ?: run {
                AppLog.put("导出书签: 没有书籍")
                return@launch
            }
            val targetPath = withContext(Dispatchers.IO) {
                val dialog = FileDialog(Frame(), "导出书签 JSON", FileDialog.SAVE)
                dialog.setFile("bookmark-${book.name} ${book.author}.json")
                dialog.isVisible = true
                val dir = dialog.directory ?: return@withContext null
                val file = dialog.file ?: return@withContext null
                dir + file
            } ?: run {
                AppLog.put("导出书签: 用户取消选择")
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val bookmarks = AppDatabaseProviders.get().appDb.bookmarkDao.getByBook(book.name, book.author)
                    File(targetPath).writeText(GSON.toJson(bookmarks))
                }
                Toasters.get().toast("导出成功")
            }.onFailure {
                AppLog.put("导出失败\n${it.localizedMessage}", it, true)
            }
        }
    }

    override fun exportBookmarkMd() {
        // 导出当前书书签为 Markdown: FileDialog SAVE → bookmarkDao.getByBook → Markdown 拼接 → 写文件
        // 对照 app 端 TocViewModel.saveBookmarkMd (SAF 选目录 + outputStream.write)
        scope.launch {
            val book = bookState.value ?: run {
                AppLog.put("导出书签: 没有书籍")
                return@launch
            }
            val targetPath = withContext(Dispatchers.IO) {
                val dialog = FileDialog(Frame(), "导出书签 Markdown", FileDialog.SAVE)
                dialog.setFile("bookmark-${book.name} ${book.author}.md")
                dialog.isVisible = true
                val dir = dialog.directory ?: return@withContext null
                val file = dialog.file ?: return@withContext null
                dir + file
            } ?: run {
                AppLog.put("导出书签: 用户取消选择")
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val bookmarks = AppDatabaseProviders.get().appDb.bookmarkDao.getByBook(book.name, book.author)
                    val sb = StringBuilder()
                    sb.append("## ${book.name} ${book.author}\n\n")
                    bookmarks.forEach {
                        sb.append("#### ${it.chapterName}\n\n")
                        sb.append("###### 原文\n ${it.bookText}\n\n")
                        sb.append("###### 摘要\n ${it.content}\n\n")
                    }
                    File(targetPath).writeText(sb.toString())
                }
                Toasters.get().toast("导出成功")
            }.onFailure {
                AppLog.put("导出失败\n${it.localizedMessage}", it, true)
            }
        }
    }

    override fun showLog() {
        // 触发应用日志对话框显示 (外层 Composable 控制 showLogDialog 状态, 末尾 AppLogDialog 渲染)
        onShowLogCb.invoke()
    }

    override fun openBookmark(bookmark: Bookmark) {
        // 书签点击 → 调 onChapterClick 切到阅读页 (对照 TocActivity.openBookmark)
        // 注: 桌面端简化不传 chapterPos, 阅读页从章节起始位置开始
        onChapterClick.invoke(bookmark.chapterIndex)
    }

    override fun editBookmark(bookmark: Bookmark, pos: Int) {
        // 触发 BookmarkDialog 显示 (外层 Composable 控制 showBookmarkDialog + editingBookmark 状态,
        // onConfirm 调 bookmarkDao.update, onDelete 调 bookmarkDao.delete, 替代原 no-op TODO)
        onEditBookmarkCb.invoke(bookmark)
    }

    override fun onChapterLongClick(title: String) {
        // 章节长按提示 (对照 TocActivity.onChapterLongClick → longToastOnUi(title))
        Toasters.get().toastLong(title)
    }
}
