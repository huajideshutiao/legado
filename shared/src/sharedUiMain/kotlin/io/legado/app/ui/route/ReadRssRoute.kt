package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

/**
 * RSS 阅读 shared 路由入口。
 *
 * 通过 [ScreenModelStore] 复用 [ReadRssScreenModel], 渲染 [ReadRssScreen]。
 * WebView 容器由平台 slot 注入, 当前 shared 侧传空实现 (app 端 Activity 接管 WebView)。
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

    // 初始化标题为书名 (对照 ReadRssActivity: pageTitle = viewModel.curBook?.name)
    LaunchedEffect(book) {
        screenModel.dispatch(ReadRssUiEvent.TitleChanged(book.name))
    }

    val actions = object : ReadRssUiActions {
        override fun onBack() {
            navigator.pop()
        }

        // 刷新: dispatch Load 置 loading, 实际内容抓取由平台 WebView slot 接管
        override fun onRefresh() = screenModel.dispatch(ReadRssUiEvent.Load)

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

    // L3: WebView 容器 (VisibleWebView + RefreshProgressBar + customViewContainer) 依赖
    // android.webkit / Glide / ChromeClient, 由平台注入, 此处先空实现
    ReadRssScreen(
        state = state,
        actions = actions,
        platformWebViewSlot = {},
    )
}
