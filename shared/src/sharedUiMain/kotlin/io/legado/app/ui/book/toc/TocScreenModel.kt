package io.legado.app.ui.book.toc

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.IntentData
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 目录页 shared ScreenModel：托管 [TocUiState] 与多协程管理，通过 [dispatch] 处理 UI 事件。
 *
 * 宿主 Activity 只负责平台专属行为 (路由跳转 / showDialogFragment / WaitDialog 实例 /
 * EventBus 观察 / 文件导出)，状态与数据操作全部在本类完成。
 *
 * @param getChapterFiles 平台专属: 列出书籍已缓存章节文件名
 *   (app 端用 `BookHelp.getChapterFiles(book)`)
 */
class TocScreenModel(
    private val getChapterFiles: (Book) -> Set<String>,
) : ScreenModel {

    private val appDb get() = AppDbProviders.get()

    // 自管 scope (app 端无 ScreenModelStore 时由宿主 DisposableEffect 调 onCleared)
    private val scope = screenModelScope("目录")

    private val _state = MutableStateFlow(TocUiState.Empty)
    val state: StateFlow<TocUiState> = _state.asStateFlow()

    // waitDialog 仅显隐状态, 实例由宿主 Activity 持有
    private val _waitDialog = MutableStateFlow(false)
    val waitDialog: StateFlow<Boolean> = _waitDialog.asStateFlow()

    // 净化标题内部累积, 批量 flush 到 state
    private val displayTitles = linkedMapOf<String, String>()

    // 内存章节表快照 (IntentData 消费一次即失效, 搜索/重载要复用)
    private var memoryChapterList: List<BookChapter>? = null

    // 多协程管理: 章节加载 / 净化标题 / 书签订阅
    private var chaptersJob: Job? = null
    private var displayTitleJob: Job? = null
    private var bookmarkJob: Job? = null

    fun dispatch(event: TocUiEvent) {
        when (event) {
            is TocUiEvent.SetBook -> setBook(event.book)
            is TocUiEvent.SetSearchMode -> setSearchMode(event.active)
            is TocUiEvent.SetQuery -> setQuery(event.query)
            is TocUiEvent.ToggleVolume -> toggleVolume(event.volume)
            is TocUiEvent.ReverseChapterList -> reverseChapterListInternal(event.list)
            TocUiEvent.ToggleUseReplace -> toggleUseReplace()
            TocUiEvent.ToggleCountWords -> toggleCountWords()
            is TocUiEvent.InitCacheFileNames -> initCacheFileNames(event.book)
            is TocUiEvent.AddCacheFile -> addCacheFile(event.name)
            is TocUiEvent.LoadChapters -> upChapterList(event.searchKey)
            TocUiEvent.LoadBookmarks -> upBookmarks()
            is TocUiEvent.UpBookTocRule -> upBookTocRule(event.book)
            is TocUiEvent.ScrollToChapter -> scrollToChapter(event.pos)
            is TocUiEvent.ScrollToBookmark -> scrollToBookmark(event.pos)
            TocUiEvent.ShowWaitDialog -> _waitDialog.value = true
            TocUiEvent.HideWaitDialog -> _waitDialog.value = false
        }
    }

    // ===== 书籍初始化 =====

    private fun setBook(book: Book) {
        if (_state.value.book?.bookUrl != book.bookUrl) memoryChapterList = null
        _state.value = _state.value.copy(
            book = book,
            durChapterIndex = book.durChapterIndex,
            isLocalBook = book.isLocal,
            useReplace = AppConfigProviders.get().tocUiUseReplace,
            countWords = AppConfigProviders.get().tocCountWords,
        )
        upChapterList(null)
        initCacheFileNames(book)
        upBookmarks()
    }

    // ===== 搜索 =====

    private fun setSearchMode(active: Boolean) {
        _state.value = _state.value.copy(searching = active)
        if (!active && _state.value.searchKey.isNotEmpty()) setQuery("")
    }

    private fun setQuery(query: String) {
        _state.value = _state.value.copy(searchKey = query)
        upChapterList(query)
        upBookmarks()
    }

    // ===== 目录 =====

    /** 对照原 ChapterListFragment.upChapterList：内存章节表优先，DB 兜底 */
    private fun upChapterList(searchKey: String?) {
        chaptersJob?.cancel()
        val book = _state.value.book ?: return
        val bookUrl = book.bookUrl
        chaptersJob = scope.launch {
            val list = withContext(IoDispatcher) {
                val end = (book.simulatedTotalChapterNum()) - 1
                // 内存章节表来源：跨页传递的 IntentData → 本地缓存（IntentData 取一次即失效，
                // 搜索时要复用）→ 阅读页活动章节表。未加书架的书按原版语义不落库，只有内存有目录。
                val memory = IntentData.chapterList
                    ?: memoryChapterList
                    ?: ActiveReadBookRegistry.current?.chapterList?.value?.takeIf { it.isNotEmpty() }
                // totalChapterNum 尚未回填(end < 0)时不截断，否则内存目录会被裁成空
                var chapterList = memory?.let {
                    it.subList(0, if (end < 0) it.size else minOf(end + 1, it.size))
                }
                if (chapterList?.firstOrNull()?.bookUrl != bookUrl) {
                    chapterList = null
                }
                chapterList?.let { memoryChapterList = it }
                when {
                    searchKey.isNullOrBlank() ->
                        chapterList ?: appDb.bookChapterDao.getChapterList(bookUrl, 0, end)

                    else -> chapterList?.filter { it.title.contains(searchKey) }
                        ?: appDb.bookChapterDao.search(bookUrl, searchKey, 0, end)
                }
            }
            setChapterList(list)
        }
    }

    private fun setChapterList(list: List<BookChapter>) {
        val dur = _state.value.durChapterIndex
        var scrollPos = 0
        for ((position, chapter) in list.withIndex()) {
            if (chapter.index >= dur) break
            scrollPos = position
        }
        val cur = _state.value
        _state.value = cur.copy(
            chapters = list,
            collapsedVolumes = emptySet(),
            chapterScroll = TocScrollCmd(scrollPos, cur.chapterScroll.tick + 1),
        )
        displayTitles.clear()
        upDisplayTitles(scrollPos)
    }

    private fun toggleVolume(volume: BookChapter) {
        val cur = _state.value.collapsedVolumes
        _state.value = _state.value.copy(
            collapsedVolumes = if (volume.index in cur) cur - volume.index else cur + volume.index
        )
    }

    /** 净化标题异步计算，从定位处向两侧填充(对照原 upDisplayTitles) */
    private fun upDisplayTitles(startIndex: Int) {
        displayTitleJob?.cancel()
        val book = _state.value.book ?: return
        val items = _state.value.chapters
        val useReplace = AppConfigProviders.get().tocUiUseReplace && book.getUseReplaceRule()
        displayTitleJob = scope.launch(Dispatchers.Default) {
            val replaceRules = ContentProcessorProviders.get().getTitleReplaceRules(book)
            val pending = linkedMapOf<String, String>()
            suspend fun flush() {
                if (pending.isEmpty()) return
                val batch = HashMap(pending)
                pending.clear()
                displayTitles.putAll(batch)
                _state.value = _state.value.copy(displayTitleMap = displayTitles.toMap())
            }

            val order = (startIndex until items.size) + (startIndex - 1 downTo 0)
            for (i in order) {
                ensureActive()
                val item = items[i]
                if (displayTitles.containsKey(item.title) || pending.containsKey(item.title)) {
                    continue
                }
                pending[item.title] = item.getDisplayTitle(replaceRules, useReplace)
                if (pending.size >= 50) flush()
            }
            flush()
        }
    }

    private fun reverseChapterListInternal(list: List<BookChapter>) {
        if (list.isEmpty()) return
        memoryChapterList = list
        setChapterList(list)
    }

    private fun initCacheFileNames(book: Book) {
        scope.launch(IoDispatcher) {
            val names = getChapterFiles(book)
            _state.value = _state.value.copy(
                cacheFileNames = _state.value.cacheFileNames + names
            )
        }
    }

    private fun addCacheFile(name: String) {
        _state.value = _state.value.copy(
            cacheFileNames = _state.value.cacheFileNames + name
        )
    }

    // ===== 菜单开关 =====

    private fun toggleUseReplace() {
        // 对照 Activity: 先写 AppConfig, 再同步 state, 再重载章节
        val newValue = !AppConfigProviders.get().tocUiUseReplace
        AppConfigProviders.get().setTocUiUseReplace(newValue)
        _state.value = _state.value.copy(useReplace = newValue)
        displayTitleJob?.cancel()
        displayTitles.clear()
        _state.value = _state.value.copy(displayTitleMap = emptyMap())
        upChapterList(_state.value.searchKey)
    }

    private fun toggleCountWords() {
        // 对照 Activity: 先写 AppConfig, 再同步 state
        val newValue = !AppConfigProviders.get().tocCountWords
        AppConfigProviders.get().setTocCountWords(newValue)
        _state.value = _state.value.copy(countWords = newValue)
    }

    /**
     * 更新书籍 TOC 规则并刷新章节列表 (对照 Activity.upBookAndToc + TocViewModelShared.upBookTocRule)。
     *
     * - 显示等待对话框 (宿主观察 [waitDialog] StateFlow)
     * - 重新拉取本地章节列表 (FileBook.getChapterList)
     * - 持久化 books/bookChapters 表
     * - postEvent 通知 ReadBook 同步 (替代 app 端 ReadBook.onChapterListUpdated)
     * - 更新 state.book + durChapterIndex, 重新加载 UI 章节列表 / 缓存文件名 / 书签
     *   (对照 Activity.bookData.observe 在 bookData 变化后触发的全套刷新)
     * - 隐藏等待对话框
     * - 失败时记录 AppLog (替代 app 端 ReadBook.upMsg("LoadTocError:..."))
     */
    private fun upBookTocRule(book: Book) {
        _waitDialog.value = true
        scope.launch(IoDispatcher) {
            try {
                appDb.bookDao.update(book)
                val chapters = FileBook.getChapterList(book)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
                appDb.bookDao.update(book)
                memoryChapterList = chapters
                postEvent(EventBus.UP_BOOKSHELF, book.bookUrl)
                _state.value = _state.value.copy(
                    book = book,
                    durChapterIndex = book.durChapterIndex,
                )
                upChapterList(null)
                initCacheFileNames(book)
                upBookmarks()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                AppLog.put("LoadTocError:${e.message}", e)
            } finally {
                _waitDialog.value = false
            }
        }
    }

    private fun scrollToChapter(pos: Int) {
        val cur = _state.value
        _state.value = cur.copy(
            chapterScroll = TocScrollCmd(pos, cur.chapterScroll.tick + 1)
        )
    }

    private fun scrollToBookmark(pos: Int) {
        val cur = _state.value
        _state.value = cur.copy(
            bookmarkScroll = TocScrollCmd(pos, cur.bookmarkScroll.tick + 1)
        )
    }

    // ===== 书签 =====

    private fun upBookmarks() {
        val book = _state.value.book ?: return
        val searchKey = _state.value.searchKey
        bookmarkJob?.cancel()
        bookmarkJob = scope.launch {
            when {
                searchKey.isBlank() -> appDb.bookmarkDao.flowByBook(book.name, book.author)
                else -> appDb.bookmarkDao.flowSearch(book.name, book.author, searchKey)
            }.catch {
                AppLog.put("目录界面获取书签数据失败\n${it.message}", it)
            }.flowOn(IoDispatcher).collect { list ->
                val dur = _state.value.durChapterIndex
                var scrollPos = 0
                for ((position, bookmark) in list.withIndex()) {
                    if (bookmark.chapterIndex >= dur) break
                    scrollPos = position
                }
                val cur = _state.value
                _state.value = cur.copy(
                    bookmarks = list,
                    bookmarkScroll = TocScrollCmd(scrollPos, cur.bookmarkScroll.tick + 1),
                )
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}

/** TocScreen 可下沉处理的 UI 事件 (平台相关事件如 openChapter/openBookmark 仍走 TocUiActions)。 */
sealed interface TocUiEvent {
    /** 书籍加载完成 (来自 viewModel.bookData observe)。 */
    data class SetBook(val book: Book) : TocUiEvent

    /** 切换搜索模式 (对照 TocUiActions.setSearchMode)。 */
    data class SetSearchMode(val active: Boolean) : TocUiEvent

    /** 设置搜索关键字 (对照 TocUiActions.setQuery)。 */
    data class SetQuery(val query: String) : TocUiEvent

    /** 折叠/展开卷 (对照 TocUiActions.toggleVolume)。 */
    data class ToggleVolume(val volume: BookChapter) : TocUiEvent

    /** 反转章节列表 (已重排 index, 持久化由宿主调用 viewModel.reverseToc)。 */
    data class ReverseChapterList(val list: List<BookChapter>) : TocUiEvent

    /** 切换净化标题开关 (AppConfig 写回由宿主负责)。 */
    object ToggleUseReplace : TocUiEvent

    /** 切换字数显示开关 (AppConfig 写回由宿主负责)。 */
    object ToggleCountWords : TocUiEvent

    /** 初始化书籍缓存文件名集合。 */
    data class InitCacheFileNames(val book: Book) : TocUiEvent

    /** 增量添加缓存文件名 (来自 SAVE_CONTENT 事件)。 */
    data class AddCacheFile(val name: String) : TocUiEvent

    /** 重新加载章节列表 (searchKey 为空表示全部)。 */
    data class LoadChapters(val searchKey: String?) : TocUiEvent

    /** 重新加载书签列表。 */
    object LoadBookmarks : TocUiEvent

    /** 更新书籍 TOC 规则并刷新章节 (对照 Activity.upBookAndToc)。 */
    data class UpBookTocRule(val book: Book) : TocUiEvent

    /** 章节列表滚动定位。 */
    data class ScrollToChapter(val pos: Int) : TocUiEvent

    /** 书签列表滚动定位。 */
    data class ScrollToBookmark(val pos: Int) : TocUiEvent

    /** 显示等待对话框 (宿主实例化 WaitDialog)。 */
    object ShowWaitDialog : TocUiEvent

    /** 隐藏等待对话框。 */
    object HideWaitDialog : TocUiEvent
}
