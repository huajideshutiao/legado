package io.legado.app.model

import io.legado.app.api.controller.ReadBookStateProvider
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.media.SystemMediaControl
import io.legado.app.help.tts.ReadAloudChapterDataHostPort
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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
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
    fun moveToNextPage()
    fun uploadProgress()
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
    fun moveToNextPage() = ownerSnapshot?.moveToNextPage()
    fun uploadProgress() = ownerSnapshot?.uploadProgress()

    // 朗读进度→阅读页翻页联动游标 (原版散在朗读服务内的 pageIndex, 下沉后单例持有)。
    // 登记起点 (阅读页 UI 线程 / 朗读服务主线程) 与推进 (Android TTS 回调的 binder 线程、
    // 其余端朗读宿主的 Dispatchers.Default) 分属不同线程, 游标与起点成对读写, 故用锁收口
    // (与同模块 RemoteZipWrapper 同一写法)。
    private val readAloudPageLock = SynchronizedObject()
    private var readAloudPageCursor: Int = -1
    private var readAloudPageStartPos: Int = 0

    /**
     * 登记一次新朗读会话的起点 (对照原版 newReadAloud 开头的 pageIndex 重置);
     * 游标页在排版产物就绪后由 [advanceReadAloudPage] 反算。
     */
    override fun resetReadAloudPageCursor(chapterPosition: Int) {
        synchronized(readAloudPageLock) {
            readAloudPageStartPos = chapterPosition.coerceAtLeast(0)
            readAloudPageCursor = -1
        }
    }

    /**
     * 朗读位置推进联动: 朗读位置越过下一页起点时把阅读页翻过去 (对照原版
     * TTSReadAloudService onStart/onRangeStart 与 HttpReadAloudService upPlayPos 的判定)。
     *
     * 翻页回调在锁外调 (阅读页实现可能反向进入朗读宿主, 与 ReadAloudControllerShared
     * 的"锁外调 navigator"同一约定)。
     */
    override fun advanceReadAloudPage(chapterIndex: Int, readingPos: Int): Boolean {
        val chapter = currentChapter ?: return false
        if (chapter.chapterIndex != chapterIndex) return false
        val advanced = synchronized(readAloudPageLock) {
            if (readAloudPageCursor < 0) {
                readAloudPageCursor = chapter.getPageIndexByCharIndex(readAloudPageStartPos)
            }
            if (readAloudPageCursor + 1 < chapter.pageSize
                && readingPos > chapter.getReadLength(readAloudPageCursor + 1)
            ) {
                readAloudPageCursor++
                true
            } else {
                false
            }
        }
        if (advanced) moveToNextPage()
        return advanced
    }

    /**
     * 章内位置所在页的页首偏移。
     *
     * 朗读自己翻页后阅读位置就停在页首, 朗读宿主据此把"自己翻的页"与"用户手动翻页"区分开
     * (见 [io.legado.app.help.tts.ReadAloudHostShared] 的翻页监听)。
     */
    override fun pageStartOf(chapterIndex: Int, chapterPosition: Int): Int? {
        val chapter = currentChapter ?: return null
        if (chapter.chapterIndex != chapterIndex) return null
        val pageIndex = chapter.getPageIndexByCharIndex(chapterPosition)
        if (pageIndex < 0 || pageIndex >= chapter.pageSize) return null
        return chapter.getReadLength(pageIndex)
    }

    override val currentPosition: ReadAloudPosition?
        get() = ownerSnapshot?.readBook?.let {
            ReadAloudPosition(it.durChapterIndexValue, it.durChapterPosValue)
        }

    override val positionUpdates: Flow<ReadAloudPosition> =
        _owner.flatMapLatest { it?.positionUpdates ?: kotlinx.coroutines.flow.emptyFlow() }

    override val chapterUpdates: Flow<ReadAloudChapterUpdate> =
        _owner.flatMapLatest { it?.chapterUpdates ?: kotlinx.coroutines.flow.emptyFlow() }

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
