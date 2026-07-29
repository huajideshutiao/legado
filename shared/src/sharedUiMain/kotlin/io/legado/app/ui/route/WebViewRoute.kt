package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * WebView shared 路由入口。
 *
 * 强平台页面 (依赖 Android WebView / iOS WKWebView / 鸿蒙 Web_Controller / Desktop JavaFX WebView),
 * 各端 WebView API 差异大, 暂未下沉 shared Screen。
 *
 * 在 WebView 能力抽象下沉前, 降级为调用平台能力用系统浏览器打开 URL 后返回,
 * 覆盖源验证/登录等场景的最小可用路径 (对照 app 端 WebViewActivity 溢出菜单 "浏览器打开")。
 */
@Composable
fun WebViewRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    @Suppress("UNUSED_PARAMETER") screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.WebView
    val url = route.url
    // shared 端无 WebView 能力, 降级为系统浏览器打开 URL 后返回
    LaunchedEffect(url) {
        PlatformCapabilityProviders.get().openExternalUrl(url)
        navigator.pop()
    }
}
