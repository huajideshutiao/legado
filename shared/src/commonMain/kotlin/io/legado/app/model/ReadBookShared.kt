package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.book.read.page.entities.TextChapterRef
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
 * curTextChapter / inBookshelf / webBookProgress。
 *
 * 注: 任务描述里的 `WebBookProgress` 实际类型为 [BookProgress]
 * (app 端 `ReadBook.webBookProgress: BookProgress?`, shared/commonMain 未单独定义 WebBookProgress 类,
 * 直接复用 [BookProgress])。
 *
 * 核心方法 (loadBook/loadChapter/nextPage/prevPage) 在 commonMain 仅提供方法签名与简单状态维护,
 * 涉及 BookHelp/CacheBook/WebBook/ReadBookConfig 等 app 依赖的具体逻辑由 actual 平台实现补充。
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

    private val _curTextChapter = MutableStateFlow<TextChapterRef?>(null)
    val curTextChapter: StateFlow<TextChapterRef?> = _curTextChapter.asStateFlow()

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
        val isDiffBook = _book.value?.bookUrl != book.bookUrl
        _book.value = book
        if (isDiffBook) {
            _chapterList.value = emptyList()
            chapterSize = 0
            simulatedChapterSize = 0
            _curTextChapter.value = null
            _webBookProgress.value = null
        }
        _durChapterIndex.value = book.durChapterIndex
        _durChapterPos.value = book.durChapterPos
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

    /** 下一页。返回 true 表示成功翻页, false 表示已到章节末尾需 actual 触发 moveToNextChapter。 */
    fun nextPage(): Boolean {
        // TODO: actual 平台补全 curTextChapter.getNextPageLength 逻辑 (app 端 ReadBook.moveToNextPage)
        callback?.onPageChanged()
        return false
    }

    /** 上一页。返回 true 表示成功翻页, false 表示已到章节首页需 actual 触发 moveToPrevChapter。 */
    fun prevPage(): Boolean {
        // TODO: actual 平台补全 curTextChapter.getPrevPageLength 逻辑 (app 端 ReadBook.moveToPrevPage)
        callback?.onPageChanged()
        return false
    }
    // endregion

    // region 辅助状态更新: 给 actual 实现调用 (Android=app.ReadBook 桥接 / 桌面=DesktopReadBookProvider)
    /** actual 端加载完章节列表后调用, 同步 chapterSize 并通知 callback */
    fun updateChapterList(list: List<BookChapter>) {
        _chapterList.value = list
        chapterSize = list.size
        simulatedChapterSize = list.size
        callback?.onChapterListChanged(list)
    }

    /** actual 端加载完 TextChapter 后调用 */
    fun updateCurTextChapter(textChapter: TextChapterRef?) {
        _curTextChapter.value = textChapter
        callback?.onContentChanged()
    }

    /** actual 端切页时调用 (durChapterPos 变化) */
    fun updateDurChapterPos(pos: Int) {
        _durChapterPos.value = pos
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
