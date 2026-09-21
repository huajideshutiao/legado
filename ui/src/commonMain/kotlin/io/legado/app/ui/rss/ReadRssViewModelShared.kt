package io.legado.app.ui.rss

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.rss.RssContentResult
import io.legado.app.model.rss.RssHelp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RSS 文章阅读 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * 对照 app 端 [io.legado.app.ui.book.rss.ReadRssViewModel] 的 `initData` / `loadContent` / `refresh`,
 * 数据源与三态分支 (intro / contentRule / 无规则) 一致, 加载编排委托 [RssHelp.loadRssContent]。
 *
 * # 状态
 *
 * 用 [StateFlow] 暴露 [ReadRssUiState], 宿主用 `collectAsState` 订阅:
 * - [ReadRssUiState.Loading]: 居中转圈
 * - [ReadRssUiState.Content]: 正文 HTML (已 clHtml 包装) + baseUrl + UA, 交平台 WebView
 *   `loadDataWithBaseURL` 渲染 (图片/视频/webJs 均由 WebView 负责, 与原版一致)
 * - [ReadRssUiState.Url]: 无正文规则, 交平台 WebView `loadUrl(url, headerMap)`
 * - [ReadRssUiState.Error]: 加载失败
 *
 * # 不下沉项 (留各端 UI 层)
 *
 * - WebView 容器本身 (经 `LocalWebViewSlot` 平台注入)
 * - 浏览器打开外链 / 分享 (经 `PlatformCapabilities`)
 *
 * @param scope 调用方提供的协程作用域
 */
class ReadRssViewModelShared(
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<ReadRssUiState>(ReadRssUiState.Loading)

    /** 正文 UI 状态, 宿主 collectAsState 订阅。 */
    val state: StateFlow<ReadRssUiState> = _state.asStateFlow()

    /**
     * 拉取正文。可重复调用 (顶栏刷新按钮触发)。
     *
     * @param book RSS 源对应的 Book (type 含 BookType.rss 位)
     * @param chapterIndex 章节 index (文章在章节列表中的位置)
     */
    fun loadContent(book: Book, chapterIndex: Int) {
        _state.value = ReadRssUiState.Loading
        scope.launch {
            withContext(IoDispatcher) {
                _state.value = when (val result = RssHelp.loadRssContent(book, chapterIndex)) {
                    is RssContentResult.Content -> ReadRssUiState.Content(
                        html = result.html,
                        baseUrl = result.baseUrl,
                        userAgent = result.userAgent,
                        chapter = result.chapter,
                    )

                    is RssContentResult.Url -> ReadRssUiState.Url(
                        url = result.url,
                        headerMap = result.headerMap,
                        userAgent = result.userAgent,
                        chapter = result.chapter,
                    )

                    is RssContentResult.Error -> ReadRssUiState.Error(
                        message = result.message,
                        chapter = result.chapter,
                    )
                }
            }
        }
    }
}

/**
 * RSS 正文阅读 UI 状态。
 *
 * [chapter] 在各态中供 UI 显示标题 + "浏览器打开" 按钮
 * (intro 分支与 Error 分支为 null, 此时回退 book.tocUrl)。
 */
sealed class ReadRssUiState {

    abstract val chapter: BookChapter?

    /** 加载中 (居中转圈)。 */
    data object Loading : ReadRssUiState() {
        override val chapter: BookChapter? get() = null
    }

    /** 正文 HTML (已 clHtml 包装), 交 WebView loadDataWithBaseURL。 */
    data class Content(
        val html: String,
        val baseUrl: String,
        val userAgent: String?,
        override val chapter: BookChapter?,
    ) : ReadRssUiState()

    /** 无正文规则: 交 WebView loadUrl(url, headerMap)。 */
    data class Url(
        val url: String,
        val headerMap: Map<String, String>,
        val userAgent: String?,
        override val chapter: BookChapter,
    ) : ReadRssUiState()

    /** 加载失败 (message 供 UI 显示)。 */
    data class Error(
        val message: String,
        override val chapter: BookChapter?,
    ) : ReadRssUiState()
}
