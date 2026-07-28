package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨平台 ReadBook 状态承载类（KMP shared/commonMain 版本）。
 *
 * 替代 app 模块的 `io.legado.app.model.ReadBook` 单例:
 * - 单例 -> 实例化注入 (class 而非 object), 由 [ReadBookProvider] 经 CompositionLocal 提供
 * - 可变 var -> MutableStateFlow, 外部只读 StateFlow, 适配 Compose 自动重组
 * - 不含 Android Intent/Activity 依赖, 桌面/iOS/鸿蒙端可直接使用
 *
 * 字段与 app 端 `ReadBook` 单例保持一一对应 (除 Android 专属 callBack 接口下沉为 [ReadBookCallback]):
 * book / bookSource / chapterList / durChapterIndex / durChapterPos / durPageIndex /
 * prevTextChapter / curTextChapter / nextTextChapter (三章滑窗) / inBookshelf / webBookProgress。
 *
 * 注: 任务描述里的 `WebBookProgress` 实际类型为 [BookProgress]
 * (app 端 `ReadBook.webBookProgress: BookProgress?`, shared/commonMain 未单独定义 WebBookProgress 类,
 * 直接复用 [BookProgress])。
 *
 * nextPage/prevPage 基于 [TextChapterShared] 的分页位移已按原版 ReadBook.moveToNextPage/moveToPrevPage
 * 下沉; 章节内容 IO/排版编排由 ReadBookViewModelShared 驱动。
 */
class ReadBookShared {

    // region 状态字段: MutableStateFlow 内部可写, 外部只读 StateFlow (适配 Compose 重组)
    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _bookSource = MutableStateFlow<BookSource?>(null)
    val bookSource: StateFlow<BookSource?> = _bookSource.asStateFlow()

    private val _chapterList = MutableStateFlow<List<BookChapter>>(emptyList())
    val chapterList: StateFlow<List<BookChapter>> = _chapterList.asStateFlow()

    private val _durChapterIndex = MutableStateFlow(0)
    val durChapterIndex: StateFlow<Int> = _durChapterIndex.asStateFlow()

    private val _durChapterPos = MutableStateFlow(0)
    val durChapterPos: StateFlow<Int> = _durChapterPos.asStateFlow()

    private val _durPageIndex = MutableStateFlow(0)
    val durPageIndex: StateFlow<Int> = _durPageIndex.asStateFlow()

    private val _prevTextChapter = MutableStateFlow<TextChapterShared?>(null)
    val prevTextChapter: StateFlow<TextChapterShared?> = _prevTextChapter.asStateFlow()

    private val _curTextChapter = MutableStateFlow<TextChapterShared?>(null)
    val curTextChapter: StateFlow<TextChapterShared?> = _curTextChapter.asStateFlow()

    private val _nextTextChapter = MutableStateFlow<TextChapterShared?>(null)
    val nextTextChapter: StateFlow<TextChapterShared?> = _nextTextChapter.asStateFlow()

    private val _inBookshelf = MutableStateFlow(false)
    val inBookshelf: StateFlow<Boolean> = _inBookshelf.asStateFlow()

    private val _webBookProgress = MutableStateFlow<BookProgress?>(null)
    val webBookProgress: StateFlow<BookProgress?> = _webBookProgress.asStateFlow()
    // endregion

    /** 章节总数 (chapterList.size 缓存, 与 app 端 ReadBook.chapterSize 对应) */
    var chapterSize: Int = 0
        private set

    /** 模拟章节总数 (与 app 端 ReadBook.simulatedChapterSize 对应, 卷/合集展开后总数) */
    var simulatedChapterSize: Int = 0
        private set

    /**
     * 回调接口。各平台 actual (Android=Activity / 桌面=Compose 适配器) 注入实现,
     * commonMain 仅持有引用, 不强依赖 Android View/Activity 生命周期。
     */
    var callback: ReadBookCallback? = null

    // region 核心方法: commonMain 仅维护状态, 实际 IO/排版/下载由 actual 平台补全
    /**
     * 装载新书 / 切书。
     * 与 app 端 `ReadBook.initData(book)` 对应, 但仅维护 shared 状态字段,
     * chapterSize/BookSource 加载/ContentProcessor 初始化等平台逻辑留 actual。
     */
    fun loadBook(book: Book) {
        val initState = calculateReadBookInitState(
            previousBookUrl = _book.value?.bookUrl,
            firstChapterBookUrl = _chapterList.value.firstOrNull()?.bookUrl,
            currentChapterIndex = _durChapterIndex.value,
            incomingBook = book,
        )
        _book.value = book
        if (initState.shouldDropChapterList) {
            _chapterList.value = emptyList()
            chapterSize = 0
            simulatedChapterSize = 0
        }
        if (initState.isDifferentBook) {
            clearTextChapter()
            _webBookProgress.value = null
        }
        if (initState.shouldResetProgress) {
            _durChapterIndex.value = initState.chapterIndex
            _durChapterPos.value = initState.chapterPosition
            clearTextChapter()
        }
        callback?.onBookChanged(book)
    }

    /**
     * 加载指定章节内容 (当前/前一章/后一章)。
     * 与 app 端 `ReadBook.loadContent(index, ...)` 对应,
     * 平台 actual 负责 BookHelp/CacheBook 下载排版。
     */
    fun loadChapter(index: Int) {
        if (index < 0 || index >= chapterSize) return
        callback?.onChapterChanged(index)
    }

    /** 当前页序号, 由 durChapterPos 反算 (对照 app 端 ReadBook.durPageIndex 计算属性)。 */
    val durPageIndexValue: Int
        get() = _curTextChapter.value?.getPageIndexByCharIndex(_durChapterPos.value)
            ?: _durChapterPos.value

    /** 下一页: durChapterPos 位移到下一页首字符 (对照 app 端 ReadBook.moveToNextPage)。false=已到章末需切章。 */
    fun nextPage(): Boolean {
        var hasNextPage = false
        _curTextChapter.value?.let {
            val nextPagePos = it.getNextPageLength(_durChapterPos.value)
            if (nextPagePos >= 0) {
                hasNextPage = true
                it.getPage(durPageIndexValue)?.removePageAloudSpan()
                _durChapterPos.value = nextPagePos
                _durPageIndex.value = durPageIndexValue
                callback?.onPageChanged()
            }
        }
        return hasNextPage
    }

    /** 上一页 (对照 app 端 ReadBook.moveToPrevPage)。false=已到章首需切章。 */
    fun prevPage(): Boolean {
        var hasPrevPage = false
        _curTextChapter.value?.let {
            val prevPagePos = it.getPrevPageLength(_durChapterPos.value)
            if (prevPagePos >= 0) {
                hasPrevPage = true
                _durChapterPos.value = prevPagePos
                _durPageIndex.value = durPageIndexValue
                callback?.onPageChanged()
            }
        }
        return hasPrevPage
    }
    // endregion

    // region 辅助状态更新: 给 actual 实现调用 (Android=app.ReadBook 桥接 / 桌面=DesktopReadBookProvider)
    /** actual 端加载完章节列表后调用, 同步 chapterSize 并通知 callback */
    fun updateChapterList(list: List<BookChapter>) {
        _chapterList.value = list
        chapterSize = list.size
        // 模拟阅读进度时按日解锁章节数 (原版 ReadBook.initData:114-115)
        simulatedChapterSize = _book.value?.takeIf { it.readSimulating() }
            ?.simulatedTotalChapterNum() ?: list.size
        callback?.onChapterListChanged(list)
    }

    /** actual 端加载完 TextChapter 后调用 */
    fun updateCurTextChapter(textChapter: TextChapterShared?) {
        updateTextChapter(0, textChapter)
    }

    /** 排版完成按滑窗位归位 (对照原版 contentLoadFinish 的 offset -1/0/+1 三分支) */
    fun updateTextChapter(offset: Int, textChapter: TextChapterShared?) {
        when (offset) {
            -1 -> _prevTextChapter.value = textChapter
            0 -> {
                _curTextChapter.value = textChapter
                _durPageIndex.value = durPageIndexValue
                callback?.onContentChanged()
            }
            1 -> _nextTextChapter.value = textChapter
        }
    }

    /** 滑窗前移: prev=cur, cur=next, next=null (对照原版 moveToNextChapter 的窗口平移段) */
    fun slideTextChaptersNext() {
        _prevTextChapter.value = _curTextChapter.value
        _curTextChapter.value = _nextTextChapter.value
        _nextTextChapter.value = null
        callback?.onContentChanged()
    }

    /** 滑窗后移: next=cur, cur=prev, prev=null (对照原版 moveToPrevChapter 的窗口平移段) */
    fun slideTextChaptersPrev() {
        _nextTextChapter.value = _curTextChapter.value
        _curTextChapter.value = _prevTextChapter.value
        _prevTextChapter.value = null
        callback?.onContentChanged()
    }

    /** 清空三章滑窗 (对照原版 ReadBook.clearTextChapter) */
    fun clearTextChapter() {
        _prevTextChapter.value = null
        _curTextChapter.value = null
        _nextTextChapter.value = null
    }

    /** actual 端解析到书源后调用 (对照原版 ReadBook.upWebBook 的 bookSource 赋值, 本地书传 null) */
    fun updateBookSource(source: BookSource?) {
        _bookSource.value = source
    }

    /** actual 端切页时调用 (durChapterPos 变化) */
    fun updateDurChapterPos(pos: Int) {
        _durChapterPos.value = pos
        _durPageIndex.value = durPageIndexValue
        callback?.onPageChanged()
    }

    /** actual 端切章时调用 (durChapterIndex 变化) */
    fun updateDurChapterIndex(index: Int) {
        _durChapterIndex.value = index
        callback?.onChapterChanged(index)
    }

    /** actual 端书架状态变化时调用 */
    fun updateInBookshelf(value: Boolean) {
        _inBookshelf.value = value
    }

    /** actual 端 web 进度更新 (与 app 端 ReadBook.webBookProgress 对应) */
    fun updateWebBookProgress(progress: BookProgress?) {
        _webBookProgress.value = progress
    }
    // endregion

    /**
     * 跨平台 ReadBook 回调接口。
     *
     * app 端 `ReadBook.CallBack` 含 LayoutProgressListener 等 Android 专属依赖, 无法直接下沉。
     * 本接口仅保留 5 个语义级事件, 各平台 actual 自行实现桥接:
     * - Android: app.ReadBook.CallBack -> ReadBookCallback 适配
     * - 桌面: Compose 状态触发 / ViewModel 监听
     */
    interface ReadBookCallback {
        /** 装载新书 / 切书后触发 ([loadBook] 完成) */
        fun onBookChanged(book: Book) {}

        /** durChapterIndex 变化 (切章) 后触发 */
        fun onChapterChanged(index: Int) {}

        /** durChapterPos / durPageIndex 变化 (翻页) 后触发 */
        fun onPageChanged() {}

        /** 章节列表刷新后触发 ([updateChapterList]) */
        fun onChapterListChanged(chapterList: List<BookChapter>) {}

        /** 当前章节正文加载完成 / 内容刷新后触发 ([updateCurTextChapter]) */
        fun onContentChanged() {}
    }
}
