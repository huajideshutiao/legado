package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.legado.app.ui.book.source.LoginScreen
import io.legado.app.ui.book.source.LoginScreenModel
import io.legado.app.ui.book.source.LoginUiActions
import io.legado.app.ui.book.source.LoginUiEvent
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * 书源登录 shared 路由入口。
 *
 * 解析 [AppRoute.Login.sourceUrl], 通过 [ScreenModelStore] 复用 [LoginScreenModel],
 * 渲染 [LoginScreen]。
 *
 * WebView 渲染由平台注入 [LoginScreen.platformWebViewSlot], 平台 WebView 能力待下沉, 暂空实现
 * (依赖各端 WebView 能力抽象, 对照 [WebViewRoute] 同期待接入)。
 */
@Composable
fun LoginRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.Login
    val sourceUrl = route.sourceUrl

    val screenModel = screenModelStore.getOrCreateTyped(entry) { LoginScreenModel() }
    val clipboard = LocalClipboardManager.current

    // 加载书源, 取 loginUrl 供 WebView slot
    LaunchedEffect(sourceUrl) {
        screenModel.dispatch(LoginUiEvent.Init(sourceUrl))
    }

    val state by screenModel.state.collectAsState()

    // 用户确认登录完成 -> 返回
    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) navigator.pop()
    }

    val actions = remember(navigator, clipboard, screenModel) {
        object : LoginUiActions {
            override fun onBack() {
                navigator.pop()
            }

            override fun onLogin() {
                screenModel.dispatch(LoginUiEvent.LoginComplete)
            }

            override fun onRefresh() {
                // WebView 刷新由平台 slot 处理, 此处暂无操作
                // TODO: 平台 WebView slot 下沉后, 通过 PlatformWebViewController.reload() 实现
            }

            override fun onOpenInBrowser() {
                // TODO: 平台浏览器打开, 需 PlatformCapabilities.openInBrowser(url) 下沉
            }

            override fun onCopyUrl() {
                clipboard.setText(AnnotatedString(state.loginUrl))
            }

            override fun onShowAppLog() {
                screenModel.dispatch(LoginUiEvent.ShowAppLog)
            }

            override fun onDismissAppLogDialog() {
                screenModel.dispatch(LoginUiEvent.DismissAppLogDialog)
            }
        }
    }

    LoginScreen(
        state = state,
        actions = actions,
        platformWebViewSlot = { /* WebView 待平台下沉 */ },
    )
}
