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
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.entities.TextChapterShared
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
    private var viewModel: ReadBookViewModelShared? = null

    /** 当前活动阅读状态；供非 Compose 宿主读取，不承担朗读端口适配。 */
    val current: ReadBookShared? get() = _current.value

    /** [current] 的可观察视图: 朗读宿主用它跟随阅读页的进入/退出/重建。 */
    val currentFlow: StateFlow<ReadBookShared?> = _current.asStateFlow()

    /** 当前活动阅读 ViewModel; 朗读宿主用其取正文、切章并回写朗读位置。 */
    val currentViewModel: ReadBookViewModelShared? get() = viewModel

    fun attach(value: ReadBookShared) {
        _current.value = value
    }

    fun detach(value: ReadBookShared) {
        if (_current.value === value) _current.value = null
    }

    fun attachViewModel(value: ReadBookViewModelShared) {
        viewModel = value
    }

    fun detachViewModel(value: ReadBookViewModelShared) {
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
