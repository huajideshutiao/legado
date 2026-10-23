package io.legado.app.model

import io.legado.app.api.controller.ReadBookStateProvider
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.media.SystemMediaControl
import io.legado.app.help.tts.ReadAloudChapterDataHostPort
import io.legado.app.help.tts.ReadAloudChapterSignal
import io.legado.app.help.tts.ReadAloudChapterUpdate
import io.legado.app.help.tts.ReadAloudHostPorts
import io.legado.app.help.tts.ReadAloudMediaControlPort
import io.legado.app.help.tts.ReadAloudPosition
import io.legado.app.help.tts.ReadAloudPositionPublisher
import io.legado.app.model.ActiveReadAloudHostPorts._owner
import io.legado.app.model.ActiveReadBookRegistry.current
import io.legado.app.service.ReadAloudChapterNavigationPort
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadBookViewModelPort
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import io.legado.app.ui.book.read.page.entities.isReadablePagedChapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlin.concurrent.Volatile

/** 当前 shared 阅读页的活动阅读状态。 */
object ActiveReadBookRegistry {
    private val _current = MutableStateFlow<ReadBookShared?>(null)

    @Volatile
    private var viewModel: ReadBookViewModelPort? = null

    /** 当前活动阅读状态；供非 Compose 宿主读取，不承担朗读端口适配。 */
    val current: ReadBookShared? get() = _current.value

    /** [current] 的可观察视图: 朗读宿主用它跟随阅读页的进入/退出/重建。 */
    val currentFlow: StateFlow<ReadBookShared?> = _current.asStateFlow()

    /** 当前活动阅读 ViewModel; 朗读宿主用其取正文、切章并回写朗读位置。 */
    val currentViewModel: ReadBookViewModelPort? get() = viewModel

    fun attach(value: ReadBookShared) {
        _current.value = value
    }

    fun detach(value: ReadBookShared) {
        if (_current.value === value) _current.value = null
    }

    fun attachViewModel(value: ReadBookViewModelPort) {
        viewModel = value
    }

    fun detachViewModel(value: ReadBookViewModelPort) {
        if (viewModel === value) viewModel = null
    }

    fun updateIfCurrent(book: Book) {
        val readBook = current ?: return
        if (readBook.book.value?.bookUrl == book.bookUrl) {
            // 对照 app 端 `ReadBook.book = it` (元数据刷新, 不走 initData 的切书重置)
            readBook.bookValue = book
        }
    }
}

interface ActiveReadAloudOwner {
    val readBook: ReadBookShared
    val positionUpdates: Flow<ReadAloudPosition>
    val chapterUpdates: Flow<ReadAloudChapterUpdate>
    fun chapterText(chapterIndex: Int): String?
    fun moveToChapter(chapterIndex: Int)

    /**
     * 翻到下一页; [origin] 标明是谁发起的翻页, 朗读自己翻的页不该再被当成用户翻页。
     * 返回是否真翻了页 (已到章末为 false), 朗读补翻循环据此如实收口。
     */
    fun moveToNextPage(origin: PositionOrigin = PositionOrigin.User): Boolean
    fun uploadProgress()

    /** 等章方需要重新评估的信号: 装载任务结束与阅读实例更换。 */
    val chapterSignals: Flow<ReadAloudChapterSignal>

    /** 请求装载该章正文; 不打断已在跑的装载, 结束后必定通知一次。 */
    fun prefetchChapter(chapterIndex: Int)
}

/**
 * 活动阅读页到朗读宿主的唯一适配层。具体页面在 attach 时显式注入章节、导航、位置发布；
 * 朗读宿主和 Android Service 都不再反查 currentViewModel 或直接发 UI 事件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
object ActiveReadAloudHostPorts : ReadAloudHostPorts {
    private val _owner = MutableStateFlow<ActiveReadAloudOwner?>(null)

    /** 同步调用使用的必要快照；flow 始终由 [_owner] 动态跟随重建。 */
    @Volatile
    private var ownerSnapshot: ActiveReadAloudOwner? = null

    fun attach(value: ActiveReadAloudOwner) {
        ownerSnapshot = value
        _owner.value = value
    }

    fun detach(value: ActiveReadAloudOwner) {
        if (ownerSnapshot === value) ownerSnapshot = null
        if (_owner.value === value) _owner.value = null
    }

    val currentReadBook: ReadBookShared? get() = ownerSnapshot?.readBook
    val currentChapter: TextChapterShared? get() = ownerSnapshot?.readBook?.curTextChapter?.value
    fun moveToNextPage(origin: PositionOrigin = PositionOrigin.User): Boolean =
        ownerSnapshot?.moveToNextPage(origin) ?: false
    fun uploadProgress() = ownerSnapshot?.uploadProgress()

    /**
     * 朗读位置推进联动: 朗读所在段落在阅读页之后的页上时, 把阅读页翻过去。
     *
     * 判定基准是**阅读页当前页位**与**该段所在页**的比较。另养一个游标会和阅读页的实际页位
     * 成为两套状态: 一旦段落坐标与排版坐标有偏差 (首屏只有标题、页面里的空行等), 游标就会抢
     * 在阅读页前面把页翻掉 —— 表现正是"第一段读完就跳到下一页"。
     *
     * 一次调用翻到目标页: 段比阅读页超前多页时 (长段、重定位后) 逐页补翻, 否则朗读段所在页
     * 永远追不上, 宿主会把每一次追赶都当成用户翻页。
     */
    override fun advanceReadAloudPage(chapterIndex: Int, readingPos: Int): Boolean {
        val chapter = currentChapter ?: return false
        if (chapter.chapterIndex != chapterIndex) return false
        val current = currentPosition?.takeIf { it.chapterIndex == chapterIndex }
            ?.chapterPosition ?: return false
        val currentPage = chapter.getPageIndexByCharIndex(current)
        val targetPage = chapter.getPageIndexByCharIndex(readingPos)
        if (currentPage < 0 || targetPage <= currentPage) return false
        var turned = 0
        while (turned < targetPage - currentPage) {
            // 翻不动 (章被并发切换/已到章末) 即如实报失败, 不虚报完成
            if (!moveToNextPage(PositionOrigin.ReadAloud)) return false
            turned++
        }
        return true
    }

    /**
     * 该章正文是否已排版就绪: 三章滑窗内命中该章, 且首屏不是占位/消息页。
     *
     * 与取正文的口径一致: 章末换章时朗读要读的那一章通常在 next/prev 槽, 不能只看当前槽。
     */
    override fun isChapterTextReady(chapterIndex: Int): Boolean =
        pagedChapterOf(chapterIndex) != null

    /** 三章滑窗 (前/当前/后) 里命中 [chapterIndex] 的已排版章节。 */
    private fun pagedChapterOf(chapterIndex: Int): TextChapterShared? {
        val book = ownerSnapshot?.readBook ?: return null
        return book.curTextChapter.value?.takeIf { it.isReadablePagedChapter(chapterIndex) }
            ?: book.nextTextChapter.value?.takeIf { it.isReadablePagedChapter(chapterIndex) }
            ?: book.prevTextChapter.value?.takeIf { it.isReadablePagedChapter(chapterIndex) }
    }

    override val currentPosition: ReadAloudPosition?
        get() = ownerSnapshot?.readBook?.let {
            ReadAloudPosition(it.durChapterIndexValue, it.durChapterPosValue)
        }

    override val positionUpdates: Flow<ReadAloudPosition> =
        _owner.flatMapLatest { it?.positionUpdates ?: kotlinx.coroutines.flow.emptyFlow() }

    override val chapterUpdates: Flow<ReadAloudChapterUpdate> =
        _owner.flatMapLatest { it?.chapterUpdates ?: kotlinx.coroutines.flow.emptyFlow() }

    override val chapterSourceAvailable: Boolean get() = ownerSnapshot != null

    override val chapterSignals: Flow<ReadAloudChapterSignal> =
        _owner.flatMapLatest { owner ->
            if (owner == null) {
                flowOf(ReadAloudChapterSignal.SourceSwitched)
            } else {
                // 换到新实例先让等章方重评一次: 旧实例发起的装载答复已无意义
                merge(flowOf(ReadAloudChapterSignal.SourceSwitched), owner.chapterSignals)
            }
        }

    override fun prefetchChapter(chapterIndex: Int) {
        ownerSnapshot?.prefetchChapter(chapterIndex)
    }

    override val chapterData = object : ReadAloudChapterDataHostPort {
        override val chapterCount: Int get() = ownerSnapshot?.readBook?.simulatedChapterSize ?: 0
        override fun chapterText(chapterIndex: Int): String? =
            ownerSnapshot?.chapterText(chapterIndex)
    }

    override val chapterNavigation = ReadAloudChapterNavigationPort { chapterIndex ->
        ownerSnapshot?.moveToChapter(chapterIndex)
    }

    override val positionPublisher: ReadAloudPositionPublisher
        get() = ReadBookEvents.readAloudPositionPublisher

    override val mediaControl = object : ReadAloudMediaControlPort {
        override fun sync(isPlaying: Boolean) = SystemMediaControl.syncReadAloud(isPlaying)
        override fun release() = SystemMediaControl.releaseReadAloud()
    }
}

/**
 * [ReadBookStateProvider] 的跨平台桥接: 直接读 [ActiveReadBookRegistry.current]
 * (shared 阅读页 ReaderScreenModel 进入/退出时 attach/detach, 全平台生效), 供 Web 服务
 * /deleteBook //saveBookProgress 同步"正在阅读的实例"。
 *
 * 注册: iOS/鸿蒙经 registerNativeBookControllerProviders, desktop 经
 * registerDesktopWebBookProviders, Android 经 registerAndroidWebBookProviders。
 */
object ActiveReadBookStateProvider : ReadBookStateProvider {
    private val readBook: ReadBookShared? get() = ActiveReadBookRegistry.current

    override val currentBookUrl: String? get() = readBook?.book?.value?.bookUrl
    override val currentBookName: String? get() = readBook?.book?.value?.name
    override val currentBookAuthor: String? get() = readBook?.book?.value?.author

    override fun clearCurrentBook() {
        // ReadBookShared 无置空 book 的公开 API, 解除挂接达成"无正在阅读的书"语义
        ActiveReadBookRegistry.current?.let { ActiveReadBookRegistry.detach(it) }
    }

    override fun setWebBookProgress(progress: BookProgress) {
        readBook?.updateWebBookProgress(progress)
    }
}
