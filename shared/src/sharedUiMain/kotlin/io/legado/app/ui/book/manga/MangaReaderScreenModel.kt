package io.legado.app.ui.book.manga

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.IntentData
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 漫画阅读页 shared ScreenModel: 适配 [MangaReaderViewModelShared] 各 StateFlow
 * 为统一 [MangaReaderUiState], 供 [MangaReaderScreenContent] 消费。
 *
 * 图片提取 ([MangaImageExtractor]) 依赖平台 BookHelp, 待下沉; 此处先空实现,
 * actual 平台注入后替换即可。其余章节状态/翻页/加载逻辑全部复用 shared VM。
 */
class MangaReaderScreenModel : ScreenModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    interface Platform {
        fun flowImages(bookChapter: BookChapter, content: String): Flow<String>
        val config: MangaReaderConfig
            get() = MangaReaderConfig.DEFAULT

        @Composable
        fun Image(
            url: String,
            modifier: Modifier,
            horizontal: Boolean,
            book: Book?,
            source: io.legado.app.data.entities.BookSource?,
        )
    }

    object Providers {
        @Volatile
        private var impl: Platform? = null
        fun register(platform: Platform) {
            impl = platform
        }

        fun getOrNull(): Platform? = impl
    }

    private val platform = Providers.getOrNull()
    private val imageExtractor = platform?.let { p ->
        object : MangaImageExtractor {
            override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> =
                p.flowImages(bookChapter, content)
        }
    } ?: object : MangaImageExtractor {
        override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> =
            MangaImageExtractorShared.extractImageUrls(content).asFlow()
    }

    private val shared = MangaReaderViewModelShared(
        scope = scope,
        imageExtractor = imageExtractor,
        config = platform?.config ?: MangaReaderConfig.DEFAULT,
    )

    private val _state = MutableStateFlow(MangaReaderUiState())
    val state: StateFlow<MangaReaderUiState> = _state.asStateFlow()
    val currentBook: Book? get() = shared.book.value
    val currentSource get() = shared.bookSource.value
    val platformRenderer: Platform? get() = platform

    init {
        // 合并 shared 各 StateFlow → 统一 UiState; chapterSize 为普通字段, 随内容流变化时读取
        combine(
            shared.book, shared.durChapter, shared.mangaContent,
            shared.durChapterIndex, shared.loading,
        ) { book, durChapter, mangaContent, durChapterIndex, loading ->
            _state.value.copy(
                bookName = book?.name ?: "",
                chapterTitle = durChapter?.title ?: "",
                images = mangaContent?.items
                    ?.filterIsInstance<MangaPage>()
                    ?.map { it.mImageUrl }
                    ?: emptyList(),
                curChapterIndex = durChapterIndex,
                chapterSize = shared.chapterSize,
                loading = loading,
            )
        }.combine(shared.error) { uiState, error ->
            uiState.copy(error = error?.first)
        }.onEach { _state.value = it }.launchIn(scope)
    }

    fun dispatch(event: MangaReaderUiEvent) {
        when (event) {
            is MangaReaderUiEvent.Init -> {
                // shared.initData 从 IntentData.book 取书
                IntentData.book = event.book
                // 对照 app 端 applyBookmarkPosition: chapterIndex>=0 时跳转到指定章节位置
                shared.initData(
                    overrideIndex = event.chapterIndex ?: -1,
                    overridePos = event.chapterPos ?: 0,
                )
            }
            // toFirst=true: 对照 Activity 点击区域 action 3/4, 用户主动切章跳首页+显示 loading
            MangaReaderUiEvent.NextChapter -> shared.moveToNextChapter(true)
            MangaReaderUiEvent.PrevChapter -> shared.moveToPrevChapter(true)
            is MangaReaderUiEvent.OpenChapter -> shared.openChapter(event.index, event.position)
            MangaReaderUiEvent.Retry -> shared.loadOrUpContent()
        }
    }

    override fun onCleared() {
        shared.onCleared()
        scope.cancel()
    }
}

/** 漫画阅读页 UI 状态, 字段对齐 [MangaReaderScreenContent] 入参。 */
data class MangaReaderUiState(
    val bookName: String = "",
    val chapterTitle: String = "",
    val images: List<String> = emptyList(),
    val curChapterIndex: Int = 0,
    val chapterSize: Int = 0,
    val horizontal: Boolean = false,
    val autoPageSpeed: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
)

/** ScreenModel 可处理的 UI 事件 (平台相关回调如 onBack/onOpenToc 仍走 Route)。 */
sealed interface MangaReaderUiEvent {
    /**
     * 初始化书籍 (Route 解析 BookRef.asBook() 后注入)。
     *
     * @param chapterIndex 书签跳转目标章节索引, null 表示不覆盖 (对应 app 端 intent chapterIndex 缺省)
     * @param chapterPos 书签跳转目标章节位置, 仅 chapterIndex 非空时生效 (对应 app 端 intent chapterPos)
     */
    data class Init(
        val book: Book,
        val chapterIndex: Int? = null,
        val chapterPos: Int? = null,
    ) : MangaReaderUiEvent

    /** 下一章 */
    object NextChapter : MangaReaderUiEvent

    /** 上一章 */
    object PrevChapter : MangaReaderUiEvent
    data class OpenChapter(val index: Int, val position: Int = 0) : MangaReaderUiEvent

    /** 错误重试 */
    object Retry : MangaReaderUiEvent
}
