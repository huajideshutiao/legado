package io.legado.app.ui.route

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.legado.app.ui.book.source.LoginScreen
import io.legado.app.ui.book.source.LoginScreenModel
import io.legado.app.ui.book.source.LoginUiActions
import io.legado.app.ui.book.source.LoginUiEvent
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * 书源登录 shared 路由入口。
 *
 * 对照原版 `BaseSource.showLoginDialog` 的两条分支分发:
 * - loginUi 非空 -> [SourceLoginDialog] 表单登录 (book/chapter 作为登录 JS 上下文);
 * - 否则 -> [LoginScreen] WebView 登录 (对照 `WebViewActivity` isLogin=true)。
 *
 * 源对象与 book/chapter 由 [AppRoute.Login.dataKey] 指向的内存上下文携带 (对照原版 IntentData),
 * 缺失时 [LoginScreenModel] 退化为按 sourceUrl 查库。
 *
 * WebView 渲染由平台注入 [LoginScreen.platformWebViewSlot], 经 [LocalWebViewSlot] 取平台实现。
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
    val platformCapabilities = PlatformCapabilityProviders.get()

    // 解析源 (dataKey 内存上下文优先, 其次查库), 取 loginUrl 供 WebView slot
    LaunchedEffect(sourceUrl) {
        screenModel.dispatch(LoginUiEvent.Init(sourceUrl, route.dataKey))
    }

    val state by screenModel.state.collectAsState()

    // 用户确认登录完成 -> 返回 (信号流, 重复点击也每次都投递)
    LaunchedEffect(screenModel) {
        screenModel.loginCompleteFlow.collect { navigator.pop() }
    }

    // loginUi 非空走表单登录 (对照原版 BaseSource.showLoginDialog 的 SourceLoginDialog 分支),
    // book/chapter 作为登录 JS 上下文透传
    val formSource = state.source?.takeIf { !it.loginUi.isNullOrEmpty() }

    // 刷新: 自增序号重建平台 WebView slot
    var webViewReloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(screenModel) {
        screenModel.refreshFlow.collect { webViewReloadKey++ }
    }

    val actions =
        remember(navigator, clipboard, screenModel, platformCapabilities, state.loginUrl) {
        object : LoginUiActions {
            override fun onBack() {
                navigator.pop()
            }

            override fun onLogin() {
                screenModel.dispatch(LoginUiEvent.LoginComplete)
            }

            override fun onRefresh() {
                // 重新创建平台 WebView slot，触发当前登录地址重载。
                screenModel.dispatch(LoginUiEvent.Refresh)
            }

            override fun onOpenInBrowser() {
                state.loginUrl.takeIf { it.isNotBlank() }
                    ?.let(platformCapabilities::openExternalUrl)
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

    when {
        // 源还没解析出来: 先不建 WebView, 否则表单源会闪一下空白页
        state.loading -> Box(Modifier.fillMaxSize().background(AppTheme.colors.background))

        formSource != null -> SourceLoginDialog(
            source = formSource,
            onDismiss = { navigator.pop() },
            onOpenUrl = platformCapabilities::openExternalUrl,
            book = state.book,
            chapter = state.chapter,
        )

        else -> LoginScreen(
            state = state,
            actions = actions,
            platformWebViewSlot = { url -> LocalWebViewSlot.current(url, Modifier.fillMaxSize()) },
            webViewReloadKey = webViewReloadKey,
        )
    }
}
