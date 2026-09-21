package io.legado.app.ui.book.rss

import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.browser.WebViewConfig
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * RSS 阅读 UI 状态 (immutable)。
 *
 * [webConfig] 是交给平台 WebView 的加载配置: 有正文规则时是 clHtml 包装后的 HTML
 * (`html` 非空 → loadDataWithBaseURL), 没有正文规则时是 AnalyzeUrl 解析出的地址 + 请求头
 * (`html` 为空 → loadUrl)。两种都由 WebView 渲染, 图片/视频/webJs 才和原版一致。
 *
 * 其余字段对照 app 端 ReadRssActivity 同名状态。
 */
data class ReadRssUiState(
    val isLoading: Boolean = false,
    val currArticle: BookChapter? = null,
    val pageTitle: String? = null,
    val starVisible: Boolean = false,
    val inShelf: Boolean = false,
    val ttsPlaying: Boolean = false,
    val hasLogin: Boolean = false,
    val videoFullScreen: Boolean = false,
    val webConfig: WebViewConfig? = null,
    val error: String? = null,
)

/**
 * RSS 阅读 shared ScreenModel: 托管 [ReadRssUiState]。
 * 实际抓取 (RssHelp/WebBook/AnalyzeUrl) 走 [io.legado.app.ui.rss.ReadRssViewModelShared],
 * 本类只承接 UI 状态与事件。
 */
class ReadRssScreenModel : ScreenModel {

    private val _state = MutableStateFlow(ReadRssUiState())
    val state: StateFlow<ReadRssUiState> = _state.asStateFlow()

    fun dispatch(event: ReadRssUiEvent) {
        when (event) {
            ReadRssUiEvent.Load -> _state.update { it.copy(isLoading = true) }
            ReadRssUiEvent.LoadFinished -> _state.update { it.copy(isLoading = false) }
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
            // 正文/地址就绪: 填 webConfig, 清 error
            is ReadRssUiEvent.WebContentReady -> _state.update {
                it.copy(
                    webConfig = event.config,
                    error = null,
                    isLoading = false,
                    currArticle = event.chapter ?: it.currArticle,
                )
            }
            // 加载失败
            is ReadRssUiEvent.LoadError -> _state.update {
                it.copy(error = event.message, isLoading = false, currArticle = event.chapter)
            }
        }
    }
}

sealed interface ReadRssUiEvent {
    data object Load : ReadRssUiEvent
    data object LoadFinished : ReadRssUiEvent
    data class TitleChanged(val title: String?) : ReadRssUiEvent
    data class StarMenuUpdated(
        val starVisible: Boolean,
        val inShelf: Boolean,
        val hasLogin: Boolean
    ) : ReadRssUiEvent

    data class TtsStateChanged(val playing: Boolean) : ReadRssUiEvent
    data class VideoFullScreenChanged(val fullScreen: Boolean) : ReadRssUiEvent

    /** 正文 HTML 或无规则地址就绪, 交平台 WebView 加载 */
    data class WebContentReady(
        val config: WebViewConfig,
        val chapter: BookChapter?,
    ) : ReadRssUiEvent

    /** 加载失败 */
    data class LoadError(val message: String, val chapter: BookChapter?) : ReadRssUiEvent
}

/**
 * RSS 阅读页平台相关回调契约。
 * 路由实现, 桥接收藏/分享/朗读/浏览器打开等。
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
