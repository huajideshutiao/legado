package io.legado.app.ui.book.rss

import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * RSS 阅读 UI 状态 (immutable)。
 * rssArticles: 当前源文章列表; isLoading: 加载中; currArticle: 当前展示文章。
 * 其余字段对照 app 端 ReadRssActivity 同名 var 状态。
 */
data class ReadRssUiState(
    val rssArticles: List<BookChapter> = emptyList(),
    val isLoading: Boolean = false,
    val currArticle: BookChapter? = null,
    val pageTitle: String? = null,
    val starVisible: Boolean = false,
    val inShelf: Boolean = false,
    val ttsPlaying: Boolean = false,
    val hasLogin: Boolean = false,
    val videoFullScreen: Boolean = false,
)

/**
 * RSS 阅读 shared ScreenModel: 托管 [ReadRssUiState]。
 * 实际抓取 (WebBook/AnalyzeUrl) 留在 app 端 ViewModel, 本类只承接 UI 状态与事件。
 */
class ReadRssScreenModel : ScreenModel {

    private val _state = MutableStateFlow(ReadRssUiState())
    val state: StateFlow<ReadRssUiState> = _state.asStateFlow()

    fun dispatch(event: ReadRssUiEvent) {
        when (event) {
            ReadRssUiEvent.Load -> _state.update { it.copy(isLoading = true) }
            ReadRssUiEvent.LoadFinished -> _state.update { it.copy(isLoading = false) }
            is ReadRssUiEvent.ArticlesLoaded -> _state.update {
                it.copy(rssArticles = event.articles, isLoading = false)
            }

            is ReadRssUiEvent.OpenArticle -> _state.update { it.copy(currArticle = event.article) }
            is ReadRssUiEvent.TitleChanged -> _state.update { it.copy(pageTitle = event.title) }
            is ReadRssUiEvent.StarMenuUpdated -> _state.update {
                it.copy(
                    starVisible = event.starVisible,
                    inShelf = event.inShelf,
                    hasLogin = event.hasLogin
                )
            }

            is ReadRssUiEvent.TtsStateChanged -> _state.update { it.copy(ttsPlaying = event.playing) }
            is ReadRssUiEvent.VideoFullScreenChanged -> _state.update {
                it.copy(videoFullScreen = event.fullScreen)
            }
            // 乐观切换收藏/TTS 状态, 实际书架操作与 TTS 播放由平台层接管
            ReadRssUiEvent.ToggleStar -> _state.update { it.copy(inShelf = !it.inShelf) }
            ReadRssUiEvent.ToggleReadAloud -> _state.update { it.copy(ttsPlaying = !it.ttsPlaying) }
        }
    }
}

sealed interface ReadRssUiEvent {
    object Load : ReadRssUiEvent
    object LoadFinished : ReadRssUiEvent
    data class ArticlesLoaded(val articles: List<BookChapter>) : ReadRssUiEvent
    data class OpenArticle(val article: BookChapter) : ReadRssUiEvent
    data class TitleChanged(val title: String?) : ReadRssUiEvent
    data class StarMenuUpdated(
        val starVisible: Boolean,
        val inShelf: Boolean,
        val hasLogin: Boolean
    ) : ReadRssUiEvent

    data class TtsStateChanged(val playing: Boolean) : ReadRssUiEvent
    data class VideoFullScreenChanged(val fullScreen: Boolean) : ReadRssUiEvent
    object ToggleStar : ReadRssUiEvent
    object ToggleReadAloud : ReadRssUiEvent
}

/**
 * RSS 阅读页平台相关回调契约 (供未来 shared Composable 调用)。
 * 宿主 Activity 实现, 桥接 WebView/分享/收藏/TTS 等平台依赖。
 */
interface ReadRssUiActions {
    fun onRefresh()
    fun onToggleStar()
    fun onShare()
    fun onReadAloud()
    fun onOpenInBrowser()
    fun onLogin()
    fun onBack()
}
