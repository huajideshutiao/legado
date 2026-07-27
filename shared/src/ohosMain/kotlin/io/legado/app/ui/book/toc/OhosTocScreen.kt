package io.legado.app.ui.book.toc

import androidx.compose.runtime.Composable
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
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.copyToClipboard
import io.legado.app.help.readFromClipboard
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.toc.TocScreen as SharedTocScreen
import io.legado.app.ui.book.toc.rule.TxtTocRuleEditDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.sharedStringTable
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 鸿蒙端目录页 Screen 入口 (包装 shared/sharedUiMain 的 [SharedTocScreen])。
 *
 * 对照 iOS 端 [io.legado.app.ui.book.toc.IosTocScreen] / desktop `TocScreen.kt` 包装模式,
 * 仅做鸿蒙平台适配, 业务展示与交互逻辑全部下沉到 shared/sharedUiMain:
 *
 * - **数据加载**: [LaunchedEffect] 异步查 [AppDbProviders.get].bookDao.getBook(bookUrl)
 *   加载本地完整 Book; 再异步加载章节列表 + 书签列表
 * - **UI state**: 构造 [TocUiState], 鸿蒙端简化项 (与 iOS 一致):
 *   - `displayTitleMap` 留空 (净标题异步计算依赖 ContentProcessor, 鸿蒙端未下沉)
 *   - `cacheFileNames` 留空 (依赖 BookHelp.getChapterFiles, 鸿蒙端未暴露)
 *   - `useReplace` 默认 false
 *   - `countWords` 初始化自 [AppConfigProviders.get].tocCountWords
 * - **actions**: 实现 [TocUiActions] 15 个方法, 核心动作接入真实逻辑,
 *   exportBookmark/exportBookmarkMd 简化为复制 JSON/Markdown 到剪贴板 (鸿蒙端无文件保存面板,
 *   与 iOS 端一致)
 * - **路由**: [onBack] / [onChapterClick] 由 OhosNavHost 注入,
 *   章节点击 → 切到 READER 路由 (携带 Book + chapterIndex)
 *
 * # 简化项 (与 iOS / desktop 一致)
 *
 * - 不接入 IntentData.chapterList 内存章节表
 * - 不实现净标题异步计算 (displayTitleMap 留空, 显示原始 title)
 * - 不接入 BookHelp.getChapterFiles 缓存文件名检测
 * - 不持久化 useReplace/countWords 切换 (TODO: 写回 PreferKey)
 * - toggleSplitLongChapter 暂为 no-op (依赖 FileBook, 鸿蒙端未下沉)
 *
 * @param book 目录页目标书籍 (由 OhosNavHost 注入, 内部用 bookUrl 从 DAO 加载完整 Book)
 * @param onBack 返回回调 (切回调用方路由: 详情/书架)
 * @param onChapterClick 章节点击回调 (切到 READER 路由, 参数为章节 index)
 */
@Composable
fun OhosTocScreen(
    book: Book,
    onBack: () -> Unit,
    onChapterClick: (Int) -> Unit,
) {
    // ===== 状态字段 (对照 TocActivity 同名字段, 与 iOS IosTocScreen 一致) =====
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
    val cacheFileNamesState = remember { mutableStateOf<Set<String>>(emptySet()) }
    val useReplaceState = remember { mutableStateOf(false) }
    var useReplace by useReplaceState
    val countWordsState = remember { mutableStateOf(AppConfigProviders.get().tocCountWords) }
    var countWords by countWordsState
    val chapterScrollState = remember { mutableStateOf(TocScrollCmd()) }
    var chapterScroll by chapterScrollState
    val bookmarksState = remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var bookmarks by bookmarksState
    val bookmarkScrollState = remember { mutableStateOf(TocScrollCmd()) }
    var bookmarkScroll by bookmarkScrollState

    val scope = rememberCoroutineScope()
    // 文案模板 (LaunchedEffect catch lambda 非 @Composable, 预先 remember)
    val tocLoadChapterFailedTemplate = rememberString("toc_load_chapter_failed_log")
    val tocLoadBookmarkFailedTemplate = rememberString("toc_load_bookmark_failed_log")
    var showLogDialog by remember { mutableStateOf(false) }
    var showTocRegexDialog by remember { mutableStateOf(false) }
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }

    // ===== 数据加载 (对照 TocActivity.onActivityCreated) =====
    LaunchedEffect(book.bookUrl) {
        val b = AppDbProviders.get().bookDao.getBook(book.bookUrl) ?: book
        loadedBook = b
        durChapterIndex = b.durChapterIndex
        runCatching { loadChapterList(b, "") }
            .onSuccess { setChapterListInternal(it, bookState, chaptersState, collapsedVolumesState, chapterScrollState) }
            .onFailure { AppLog.put(String.format(tocLoadChapterFailedTemplate, it.localizedMessage), it) }
        runCatching { loadBookmarks(b, "") }
            .onSuccess { setBookmarksInternal(it, bookState, bookmarksState, bookmarkScrollState) }
            .onFailure { AppLog.put(String.format(tocLoadBookmarkFailedTemplate, it.localizedMessage), it) }
    }

    val actions = remember(onBack, onChapterClick, scope) {
        OhosTocActions(
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
            onEditBookmarkCb = { bm ->
                editingBookmark = bm
                showBookmarkDialog = true
            },
        )
    }

    val state = TocUiState(
        book = loadedBook,
        durChapterIndex = durChapterIndex,
        searching = searching,
        searchKey = searchKey,
        chapters = chapters,
        collapsedVolumes = collapsedVolumes,
        displayTitleMap = emptyMap(),
        cacheFileNames = cacheFileNamesState.value,
        useReplace = useReplace,
        countWords = countWords,
        chapterScroll = chapterScroll,
        bookmarks = bookmarks,
        bookmarkScroll = bookmarkScroll,
        isLocalBook = loadedBook?.isLocal == true,
    )

    SharedTocScreen(state, actions)

    // TXT 目录规则编辑对话框 (与 iOS IosTocScreen 一致, 复用 shared/sharedUiMain TxtTocRuleEditDialog)
    if (showTocRegexDialog) {
        TxtTocRuleEditDialog(
            rule = null,
            onConfirm = { r ->
                scope.launch { AppDbProviders.get().txtTocRuleDao.insert(r) }
            },
            onDismiss = { showTocRegexDialog = false },
            clipTextProvider = { readFromClipboard() },
            clipTextSink = { text -> copyToClipboard(text) },
        )
    }
    // 应用日志对话框
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }
    // 书签编辑对话框
    if (showBookmarkDialog) {
        editingBookmark?.let { bm ->
            BookmarkDialog(
                bookmark = bm,
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
                        AppDatabaseProviders.get().appDb.bookmarkDao.delete(bm)
                        reloadBookmarks(loadedBook, searchKey, bookState, bookmarksState, bookmarkScrollState)
                    }
                    showBookmarkDialog = false
                },
            )
        }
    }
}

// ===== 辅助加载函数 (与 iOS IosTocScreen / desktop TocScreen 一致, 复用同一套查询逻辑) =====

private suspend fun loadChapterList(book: Book, key: String): List<BookChapter> {
    val dao = AppDbProviders.get().bookChapterDao
    val end = (book.simulatedTotalChapterNum() - 1).coerceAtLeast(0)
    return if (key.isBlank()) {
        dao.getChapterList(book.bookUrl, 0, end)
    } else {
        dao.search(book.bookUrl, key, 0, end)
    }
}

private suspend fun loadBookmarks(book: Book, key: String): List<Bookmark> {
    val dao = AppDatabaseProviders.get().appDb.bookmarkDao
    return if (key.isBlank()) {
        dao.getByBook(book.name, book.author)
    } else {
        dao.flowSearch(book.name, book.author, key).first()
    }
}

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
        .onFailure { AppLog.put(sharedStringTable["toc_reload_bookmark_failed_log"]!!.format(it.localizedMessage), it) }
}

/**
 * 鸿蒙端 [TocUiActions] 实现 (对照 iOS `IosTocActions` / desktop)。
 *
 * 15 个回调中, exportBookmark/exportBookmarkMd 简化为复制到剪贴板 (鸿蒙端无文件保存面板,
 * 与 iOS 端一致), toggleSplitLongChapter 暂为 no-op (依赖 FileBook, 鸿蒙端未下沉),
 * 其余与 iOS / desktop 一致。
 */
private class OhosTocActions(
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
        if (!active && searchKeyState.value.isNotEmpty()) setQuery("")
    }

    override fun setQuery(query: String) {
        searchKeyState.value = query
        scope.launch {
            val book = bookState.value ?: return@launch
            runCatching { loadChapterList(book, query) }
                .onSuccess { setChapterListInternal(it, bookState, chaptersState, collapsedVolumesState, chapterScrollState) }
                .onFailure { AppLog.put(sharedStringTable["toc_load_chapter_failed_log"]!!.format(it.localizedMessage), it) }
            runCatching { loadBookmarks(book, query) }
                .onSuccess { setBookmarksInternal(it, bookState, bookmarksState, bookmarkScrollState) }
                .onFailure { AppLog.put(sharedStringTable["toc_load_bookmark_failed_log"]!!.format(it.localizedMessage), it) }
        }
    }

    override fun toggleVolume(volume: BookChapter) {
        val current = collapsedVolumesState.value
        collapsedVolumesState.value = if (volume.index in current) current - volume.index else current + volume.index
    }

    override fun openChapter(chapter: BookChapter) = onChapterClick.invoke(chapter.index)

    override fun reverseChapterList() {
        val current = chaptersState.value
        if (current.isEmpty()) return
        val reversed = current.reversed().apply { forEachIndexed { i, c -> c.index = i } }
        setChapterListInternal(reversed, bookState, chaptersState, collapsedVolumesState, chapterScrollState)
    }

    override fun toggleUseReplace() { useReplaceState.value = !useReplaceState.value }
    override fun toggleCountWords() { countWordsState.value = !countWordsState.value }
    override fun toggleSplitLongChapter() { /* TODO: 依赖 FileBook, 鸿蒙端未下沉 (与 iOS 一致) */ }

    override fun showTocRegexDialog() = onShowTocRegexDialog.invoke()

    override fun exportBookmark() {
        // 鸿蒙端无文件保存面板, 简化为复制 JSON 到剪贴板 (与 iOS 端一致)
        scope.launch {
            val book = bookState.value ?: return@launch
            val bookmarks = withContext(Dispatchers.Default) {
                AppDatabaseProviders.get().appDb.bookmarkDao.getByBook(book.name, book.author)
            }
            copyToClipboard(GSON.toJson(bookmarks))
            Toasters.get().toast(sharedStringTable["copied_bookmarks_to_clipboard_count"]!!.format(bookmarks.size))
        }
    }

    override fun exportBookmarkMd() {
        // 鸿蒙端无文件保存面板, 简化为复制 Markdown 到剪贴板 (与 iOS 端一致)
        scope.launch {
            val book = bookState.value ?: return@launch
            val bookmarks = withContext(Dispatchers.Default) {
                AppDatabaseProviders.get().appDb.bookmarkDao.getByBook(book.name, book.author)
            }
            val sb = StringBuilder()
            sb.append("## ${book.name} ${book.author}\n\n")
            bookmarks.forEach {
                sb.append("#### ${it.chapterName}\n\n")
                sb.append("###### 原文\n ${it.bookText}\n\n")
                sb.append("###### 摘要\n ${it.content}\n\n")
            }
            copyToClipboard(sb.toString())
            Toasters.get().toast(sharedStringTable["copied_markdown_to_clipboard"]!!)
        }
    }

    override fun showLog() = onShowLogCb.invoke()
    override fun openBookmark(bookmark: Bookmark) = onChapterClick.invoke(bookmark.chapterIndex)
    override fun editBookmark(bookmark: Bookmark, pos: Int) = onEditBookmarkCb.invoke(bookmark)
    override fun onChapterLongClick(title: String) { Toasters.get().toastLong(title) }
}
