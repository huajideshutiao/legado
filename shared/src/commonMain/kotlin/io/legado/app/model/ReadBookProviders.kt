package io.legado.app.model

import io.legado.app.api.controller.ReadBookStateProvider
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.ui.book.read.ReadBookViewModelShared
import kotlin.concurrent.Volatile

/**
 * 跨平台 ReadBook Provider 注入契约。
 *
 * 与 `ui.compose.platform` 下 ThemeStoreProvider / AppConfigProvider 等 Provider 同范式:
 * - commonMain 定义 interface, sharedUiMain 定义 [LocalReadBookProvider] CompositionLocal
 * - 各平台 actual 自行构造注入, 避免 shared androidMain 反向依赖 app 模块
 *
 * 注入路径:
 * - Android: 由 app 模块在 ReadBookActivity/Compose 入口用 CompositionLocalProvider 注入,
 *   内部桥接到 app.ReadBook 单例 (向后兼容, 不破坏 100+ 处现有引用)
 * - 桌面 jvm: [io.legado.app.model.DesktopReadBookProvider] 直接 new [ReadBookShared] 实例
 * - iOS/鸿蒙: stub 实现 (KP3/KP4 替换)
 *
 * 放在 model/ 目录而非 ui/compose/platform/ 是因为 ReadBook 状态本身属于 model 层,
 * CompositionLocal 仅是注入手段 (与 ThemeStoreProvider 等 UI 状态 Provider 分层)。
 *
 * KP5: [LocalReadBookProvider] (Compose 依赖) 已拆分到 sharedUiMain 的 ReadBookProvidersUi.kt,
 * 本文件仅保留 interface 定义, 让 ohos/linuxArm64 不依赖 Compose 也能编译。
 */
interface ReadBookProvider {
    /** 跨平台 ReadBook 状态承载实例 */
    val readBook: ReadBookShared
}

/** 当前 shared 阅读页的活动阅读状态。 */
object ActiveReadBookRegistry {
    @Volatile
    private var readBook: ReadBookShared? = null

    @Volatile
    private var viewModel: ReadBookViewModelShared? = null

    /** 当前活动阅读状态; 供非 Compose 宿主 (如桌面朗读宿主) 读取章节/位置。 */
    val current: ReadBookShared? get() = readBook

    /** 当前活动阅读 ViewModel; 朗读宿主用其取正文、切章并回写朗读位置。 */
    val currentViewModel: ReadBookViewModelShared? get() = viewModel

    fun attach(value: ReadBookShared) {
        readBook = value
    }

    fun detach(value: ReadBookShared) {
        if (readBook === value) readBook = null
    }

    fun attachViewModel(value: ReadBookViewModelShared) {
        viewModel = value
    }

    fun detachViewModel(value: ReadBookViewModelShared) {
        if (viewModel === value) viewModel = null
    }

    fun updateIfCurrent(book: Book) {
        val current = readBook ?: return
        if (current.book.value?.bookUrl == book.bookUrl) {
            // 对照 app 端 `ReadBook.book = it` (元数据刷新, 不走 initData 的切书重置)
            current.bookValue = book
        }
    }
}

/**
 * [ReadBookStateProvider] 的跨平台桥接: 直接读 [ActiveReadBookRegistry.current]
 * (shared 阅读页 ReaderScreenModel 进入/退出时 attach/detach, 全平台生效), 供 Web 服务
 * /deleteBook //saveBookProgress 同步"正在阅读的实例"。
 *
 * 注册: iOS/鸿蒙经 registerNativeBookControllerProviders, desktop 经
 * registerDesktopWebBookProviders; Android 桥接 app.ReadBook 单例, 不经本实现。
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
