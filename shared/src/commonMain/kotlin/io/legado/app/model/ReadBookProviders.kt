package io.legado.app.model

import io.legado.app.api.controller.ReadBookStateProvider
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.ui.book.read.ReadBookViewModelShared
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/** 当前 shared 阅读页的活动阅读状态。 */
object ActiveReadBookRegistry {
    private val _current = MutableStateFlow<ReadBookShared?>(null)

    @Volatile
    private var viewModel: ReadBookViewModelShared? = null

    /** 当前活动阅读状态; 供非 Compose 宿主 (如朗读服务/桌面朗读宿主) 读取章节/位置。 */
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
