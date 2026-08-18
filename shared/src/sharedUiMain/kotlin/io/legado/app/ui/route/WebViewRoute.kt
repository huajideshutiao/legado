package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.ui.browser.WebViewScreen
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * WebView 全屏路由的形态壳。
 *
 * 浏览器本体一律走 [WebViewScreen] (与半屏 Sheet 同一段实现、同一个 [AppRoute.WebView] 参数包:
 * 书源 headerMap 预取 / POST 预拉 / data: 解包 / 验证回传 / CF 检测 / 跳转拦截 / 菜单 / 返回链),
 * 本函数只补两件全屏路由特有的事:
 *
 * 1. **返回拦截**: 页面级 [AppBackHandler], 仅栈顶页面生效 (isTopEntry);
 * 2. **全屏**: 原 menu_full_screen → toggleFullScreen, 除隐藏顶栏 (由 [WebViewScreen] 按
 *    fullScreen 统一处理) 外还调平台 `window.setFullscreen` 隐藏系统栏 —— 路由页独占平台窗口,
 *    半屏 Sheet 是独立对话框窗口、不掌管系统栏, 这是两形态唯一的差异点。
 */
@Composable
fun WebViewRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    @Suppress("UNUSED_PARAMETER") screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.WebView
    val backStack by navigator.backStack.collectAsState()
    val isTopEntry = backStack.lastOrNull()?.id == entry.id
    // 全屏态 (原 menu_full_screen → toggleFullScreen): 顶栏随全屏隐藏 + 系统栏隐藏
    var isFullScreen by remember { mutableStateOf(false) }
    WebViewScreen(
        spec = route,
        onClose = { navigator.pop() },
        fullScreen = isFullScreen,
        onToggleFullScreen = {
            isFullScreen = !isFullScreen
            PlatformServiceProviders.getOrNull()?.window?.setFullscreen(isFullScreen)
        },
        backHandler = { onScreenBack ->
            AppBackHandler(enabled = isTopEntry) { onScreenBack() }
        },
    )
}
