package io.legado.app.ui.book.toc

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 目录：目录/书签双 tab，纯 Compose 宿主，状态托管在 Activity。
 *
 * 实现 [TocUiActions] 接口供下沉到 shared/sharedUiMain 的 [TocScreen] 回调,
 * 已有同名方法直接 `override`；[onBack] / [onChapterLongClick] 为下沉新增适配。
 */
class TocActivity : BaseComposeActivity(), TxtTocRuleDialog.CallBack, TocUiActions {

    val viewModel by viewModels<TocViewModel>()

    /** 一次性列表定位命令，tick 去重 */
    data class ScrollCmd(val pos: Int = 0, val tick: Long = 0)

    var book by mutableStateOf<Book?>(null)
        private set
    var durChapterIndex by mutableStateOf(0)
        private set
    var searching by mutableStateOf(false)
        private set
    var searchKey by mutableStateOf("")
        private set

    // 目录页
    var chapters by mutableStateOf<List<BookChapter>>(emptyList())
        private set
    var collapsedVolumes by mutableStateOf<Set<Int>>(emptySet())
        private set
    val displayTitleMap = mutableStateMapOf<String, String>()
    var cacheFileNames by mutableStateOf<Set<String>>(emptySet())
        private set
    var useReplace by mutableStateOf(AppConfig.tocUiUseReplace)
        private set
    var countWords by mutableStateOf(AppConfig.tocCountWords)
        private set
    var chapterScroll by mutableStateOf(ScrollCmd())
        private set

    // 书签页
    var bookmarks by mutableStateOf<List<Bookmark>>(emptyList())
        private set
    var bookmarkScroll by mutableStateOf(ScrollCmd())
        private set

    val isLocalBook: Boolean get() = book?.isLocal == true

    private val waitDialog by lazy { WaitDialog.from(this) }
    private var chaptersJob: Job? = null
    private var displayTitleJob: Job? = null
    private var bookmarkJob: Job? = null
    private val exportDir by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                when (result.requestCode) {
                    1 -> viewModel.saveBookmark(uri)
                    2 -> viewModel.saveBookmarkMd(uri)
                }
            }
        }
    }

    @Composable
    override fun Content() {
        // BackHandler 由 app 端注册 (shared/sharedUiMain 未引入 activity-compose 依赖,
        // 下沉到 shared 的 TocScreen 不再内嵌 BackHandler)
        BackHandler(enabled = searching) { setSearchMode(false) }
        val state = TocUiState(
            book = book,
            durChapterIndex = durChapterIndex,
            searching = searching,
            searchKey = searchKey,
            chapters = chapters,
            collapsedVolumes = collapsedVolumes,
            displayTitleMap = displayTitleMap,
            cacheFileNames = cacheFileNames,
            useReplace = useReplace,
            countWords = countWords,
            chapterScroll = TocScrollCmd(chapterScroll.pos, chapterScroll.tick),
            bookmarks = bookmarks,
            bookmarkScroll = TocScrollCmd(bookmarkScroll.pos, bookmarkScroll.tick),
            isLocalBook = isLocalBook,
        )
        TocScreen(state = state, actions = this)
    }

    // ===== TocUiActions 适配 =====

    /** 返回回调 (替代原 TocScreen 内 `activity.finish()`)。 */
    override fun onBack() = finish()

    /** 章节长按提示 (替代原 TocScreen 内 `context.longToastOnUi(title)`)。 */
    override fun onChapterLongClick(title: String) {
        longToastOnUi(title)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.bookData.observe(this) {
            book = it
            durChapterIndex = it.durChapterIndex
            upChapterList(null)
            initCacheFileNames(it)
            upBookmarks()
        }
        viewModel.initBook()
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.bookData.value?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    cacheFileNames = cacheFileNames + chapter.getFileName()
                }
            }
        }
    }

    // ===== 搜索 =====

    // 避免与 searching 属性 setter 的 JVM 签名冲突, 不叫 setSearching
    override fun setSearchMode(active: Boolean) {
        searching = active
        if (!active && searchKey.isNotEmpty()) setQuery("")
    }

    override fun setQuery(query: String) {
        searchKey = query
        upChapterList(query)
        upBookmarks()
    }

    // ===== 目录 =====

    /** 对照原 ChapterListFragment.upChapterList：内存章节表优先，DB 兜底 */
    fun upChapterList(searchKey: String?) {
        chaptersJob?.cancel()
        chaptersJob = lifecycleScope.launch {
            withContext(IO) {
                val end = (book?.simulatedTotalChapterNum() ?: Int.MAX_VALUE) - 1
                var chapterList = IntentData.chapterList?.subList(0, end + 1)
                if (chapterList?.firstOrNull()?.bookUrl != book?.bookUrl) {
                    chapterList = null
                }
                when {
                    searchKey.isNullOrBlank() ->
                        chapterList ?: appDb.bookChapterDao.getChapterList(viewModel.bookUrl, 0, end)

                    else -> chapterList?.filter { it.title.contains(searchKey) }
                        ?: appDb.bookChapterDao.search(viewModel.bookUrl, searchKey, 0, end)
                }
            }.let {
                setChapterList(it)
            }
        }
    }

    private fun setChapterList(list: List<BookChapter>) {
        collapsedVolumes = emptySet()
        chapters = list
        // 对照原 onListChanged：定位到当前章前一条
        var scrollPos = 0
        for ((position, chapter) in list.withIndex()) {
            if (chapter.index >= durChapterIndex) break
            scrollPos = position
        }
        chapterScroll = ScrollCmd(scrollPos, chapterScroll.tick + 1)
        upDisplayTitles(scrollPos)
    }

    override fun toggleVolume(volume: BookChapter) {
        collapsedVolumes = if (volume.index in collapsedVolumes) {
            collapsedVolumes - volume.index
        } else {
            collapsedVolumes + volume.index
        }
    }

    /** 净化标题异步计算，从定位处向两侧填充(对照原 upDisplayTitles) */
    private fun upDisplayTitles(startIndex: Int) {
        displayTitleJob?.cancel()
        val book = book ?: return
        val items = chapters
        val useReplace = AppConfig.tocUiUseReplace && book.getUseReplaceRule()
        displayTitleJob = lifecycleScope.launch(Default) {
            val replaceRules = ContentProcessor.get(book.name, book.origin).getTitleReplaceRules()
            val pending = linkedMapOf<String, String>()
            suspend fun flush() {
                if (pending.isEmpty()) return
                val batch = HashMap(pending)
                pending.clear()
                withContext(Main) { displayTitleMap.putAll(batch) }
            }

            val order = (startIndex until items.size) + (startIndex - 1 downTo 0)
            for (i in order) {
                ensureActive()
                val item = items[i]
                if (displayTitleMap.containsKey(item.title) || pending.containsKey(item.title)) {
                    continue
                }
                pending[item.title] = item.getDisplayTitle(replaceRules, useReplace)
                if (pending.size >= 50) flush()
            }
            flush()
        }
    }

    override fun reverseChapterList() {
        val current = chapters
        if (current.isEmpty()) return
        val reversed = current.reversed().apply {
            forEachIndexed { i, c -> c.index = i }
        }
        setChapterList(reversed)
        viewModel.reverseToc(reversed)
    }

    override fun openChapter(chapter: BookChapter) {
        setResult(
            RESULT_OK, Intent()
                .putExtra("index", chapter.index)
                .putExtra("chapterChanged", chapter.index != durChapterIndex)
        )
        finish()
    }

    private fun initCacheFileNames(book: Book) {
        lifecycleScope.launch(IO) {
            val names = BookHelp.getChapterFiles(book)
            withContext(Main) { cacheFileNames = cacheFileNames + names }
        }
    }

    // ===== 菜单 =====

    override fun toggleUseReplace() {
        AppConfig.tocUiUseReplace = !AppConfig.tocUiUseReplace
        useReplace = AppConfig.tocUiUseReplace
        displayTitleJob?.cancel()
        displayTitleMap.clear()
        upChapterList(searchKey)
    }

    override fun toggleCountWords() {
        AppConfig.tocCountWords = !AppConfig.tocCountWords
        countWords = AppConfig.tocCountWords
    }

    override fun toggleSplitLongChapter() {
        viewModel.bookData.value?.let { book ->
            book.config.splitLongChapter = !book.config.splitLongChapter
            upBookAndToc(book)
        }
    }

    override fun showTocRegexDialog() {
        showDialogFragment(TxtTocRuleDialog(viewModel.bookData.value?.tocUrl))
    }

    override fun exportBookmark() = exportDir.launch { requestCode = 1 }

    override fun exportBookmarkMd() = exportDir.launch { requestCode = 2 }

    override fun showLog() = showDialogFragment<AppLogDialog>()

    override fun onTocRegexDialogResult(tocRegex: String) {
        viewModel.bookData.value?.let { book ->
            book.tocUrl = tocRegex
            upBookAndToc(book)
        }
    }

    private fun upBookAndToc(book: Book) {
        waitDialog.show(supportFragmentManager)
        viewModel.upBookTocRule(book) {
            waitDialog.dismissSafe()
            if (ReadBook.book == book) {
                if (it == null) {
                    ReadBook.upMsg(null)
                } else {
                    ReadBook.upMsg("LoadTocError:${it.localizedMessage}")
                }
            }
        }
    }

    // ===== 书签 =====

    fun upBookmarks() {
        val book = viewModel.bookData.value ?: return
        bookmarkJob?.cancel()
        bookmarkJob = lifecycleScope.launch {
            when {
                searchKey.isBlank() -> appDb.bookmarkDao.flowByBook(book.name, book.author)
                else -> appDb.bookmarkDao.flowSearch(book.name, book.author, searchKey)
            }.catch {
                AppLog.put("目录界面获取书签数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect { list ->
                bookmarks = list
                var scrollPos = 0
                for ((position, bookmark) in list.withIndex()) {
                    if (bookmark.chapterIndex >= durChapterIndex) break
                    scrollPos = position
                }
                bookmarkScroll = ScrollCmd(scrollPos, bookmarkScroll.tick + 1)
            }
        }
    }

    override fun openBookmark(bookmark: Bookmark) {
        setResult(
            RESULT_OK, Intent()
                .putExtra("index", bookmark.chapterIndex)
                .putExtra("chapterPos", bookmark.chapterPos)
        )
        finish()
    }

    override fun editBookmark(bookmark: Bookmark, pos: Int) {
        showDialogFragment(BookmarkDialog(bookmark, pos))
    }

}
