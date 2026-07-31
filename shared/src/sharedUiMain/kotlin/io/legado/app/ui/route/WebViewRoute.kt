package io.legado.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * WebView shared 路由入口。
 *
 * 经 [LocalWebViewSlot] 取各端平台实现渲染。Android 与 iOS 使用原生 WebView，
 * desktop 走系统浏览器，鸿蒙通过 NAPI 桥接平台 Web 能力。
 */
@Composable
fun WebViewRoute(
    entry: RouteEntry,
    @Suppress("UNUSED_PARAMETER") navigator: AppNavigator,
    @Suppress("UNUSED_PARAMETER") screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.WebView
    val url = route.url
    // 平台 slot 渲染 WebView，未注册平台能力时使用 shared 兜底提示。
    Box(Modifier.fillMaxSize()) {
        LocalWebViewSlot.current(url, Modifier.fillMaxSize())
    }
}
