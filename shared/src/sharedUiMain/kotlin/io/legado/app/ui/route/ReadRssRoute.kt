package io.legado.app.ui.route

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.book.rss.ReadRssScreen
import io.legado.app.ui.book.rss.ReadRssScreenModel
import io.legado.app.ui.book.rss.ReadRssUiActions
import io.legado.app.ui.book.rss.ReadRssUiEvent
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.rss.ReadRssUiState as SharedReadRssUiState
import io.legado.app.ui.rss.ReadRssViewModelShared
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.launch

/**
 * RSS 阅读 shared 路由入口。
 *
 * 通过 [ScreenModelStore] 复用 [ReadRssScreenModel], 桥接 commonMain
 * [ReadRssViewModelShared] 三态 (Content/Empty/Error) → [ReadRssUiEvent],
 * 渲染 [ReadRssScreen]。WebView 容器由 [LocalWebViewSlot] 平台注入
 * (URL-only 模式使用; HTML body 走 HtmlFormatter 纯文本渲染, 跨平台一致)。
 */
@Composable
fun ReadRssRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.ReadRss
    val book = route.book.asBook()

    val screenModel = screenModelStore.getOrCreateTyped(entry) { ReadRssScreenModel() }
    val state by screenModel.state.collectAsState()

    // 复用 commonMain 加载逻辑 (RssHelp.loadRssContent 三态分支)
    val scope = rememberCoroutineScope()
    val shared = remember { ReadRssViewModelShared(scope) }

    // 初始化: 标题为书名 + 桥接 shared 三态 → ScreenModel 事件 + 触发首次加载
    LaunchedEffect(book) {
        screenModel.dispatch(ReadRssUiEvent.TitleChanged(book.name))
        // 桥接 shared.state → ScreenModel 事件
        launch {
            shared.state.collect { s ->
                when (s) {
                    SharedReadRssUiState.Loading ->
                        screenModel.dispatch(ReadRssUiEvent.Load)

                    is SharedReadRssUiState.Content ->
                        screenModel.dispatch(ReadRssUiEvent.ContentLoaded(s.body, s.chapter))

                    is SharedReadRssUiState.Empty -> {
                        // 无正文规则: chapter.url 经 tocUrl 解析为绝对路径后交 WebView 加载
                        val resolved = NetworkUtils.getAbsoluteURL(book.tocUrl, s.chapter.url)
                        screenModel.dispatch(ReadRssUiEvent.UrlLoaded(resolved, s.chapter))
                    }

                    is SharedReadRssUiState.Error ->
                        screenModel.dispatch(ReadRssUiEvent.LoadError(s.message, s.chapter))
                }
            }
        }
        // 触发首次加载 (chapterIndex = book.durChapterIndex, 由 RssArticlesRoute 点击时写入)
        shared.loadContent(book, book.durChapterIndex)
    }

    val actions = object : ReadRssUiActions {
        override fun onBack() {
            navigator.pop()
        }

        // 刷新: 重新触发 shared.loadContent (重置 Loading 态并重新拉取)
        override fun onRefresh() {
            shared.loadContent(book, book.durChapterIndex)
        }

        // 收藏: 乐观切换 inShelf, 实际书架增删由平台层接管
        override fun onToggleStar() = screenModel.dispatch(ReadRssUiEvent.ToggleStar)

        // 分享: 分享当前文章 URL, 回退到书源 tocUrl
        override fun onShare() {
            val url = state.currArticle?.url ?: book.tocUrl
            PlatformCapabilityProviders.getOrNull()?.shareText(url)
        }

        // 朗读: 乐观切换 ttsPlaying, 实际 TTS 由平台层接管
        override fun onReadAloud() = screenModel.dispatch(ReadRssUiEvent.ToggleReadAloud)

        // 浏览器打开: 打开当前文章 URL, 回退到书源 tocUrl
        override fun onOpenInBrowser() {
            val url = state.currArticle?.url ?: book.tocUrl
            PlatformCapabilityProviders.getOrNull()?.openExternalUrl(url)
        }

        // 登录: 跳转书源登录页 (book.origin = 书源 URL)
        override fun onLogin() {
            navigator.push(AppRoute.Login(book.origin))
        }
    }

    // L3: WebView 容器经 LocalWebViewSlot 取平台 actual (Android: AndroidWebView; 其他端 stub)
    ReadRssScreen(
        state = state,
        actions = actions,
        platformWebViewSlot = { url -> LocalWebViewSlot.current(url, Modifier.fillMaxSize()) },
    )
}
