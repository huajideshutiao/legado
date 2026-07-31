package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import io.legado.app.ui.compose.platform.AppShortcut
import io.legado.app.ui.compose.platform.AppShortcutHandler
import io.legado.app.ui.compose.platform.performBack

/**
 * 全局快捷键清单。全部带 Command 修饰键 (macOS Cmd / 其余 Ctrl) 或功能键,
 * 故可在根节点捕获阶段消费而不会吞掉输入框的正常输入。
 */
object GlobalShortcuts {
    val Search = AppShortcut(Key.F, command = true)
    val Settings = AppShortcut(Key.Comma, command = true)
    val Close = AppShortcut(Key.W, command = true)
    val Quit = AppShortcut(Key.Q, command = true)

    /** 与 F5 等价并存 (F5 由 handleBackKey 直接处理)。 */
    val Refresh = AppShortcut(Key.R, command = true)
    val Fullscreen = AppShortcut(Key.F11)

    val all = listOf(Search, Settings, Close, Quit, Refresh, Fullscreen)
}

/**
 * 在应用根部注册 [GlobalShortcuts]。栈底注册, 页面级快捷键 (后注册) 恒优先。
 */
@Composable
fun AppGlobalShortcuts(navigator: AppNavigator) {
    val capabilities = LocalPlatformCapabilities.current
    val entries by navigator.backStack.collectAsState()
    val currentRoute = entries.lastOrNull()?.route

    // 全屏态以当前路由的窗口策略为基线, F11 在其上手动翻转 (窗口层无全屏状态查询接口);
    // 不按策略走全屏的平台 (desktop) 基线恒 false, 否则进阅读页后首次 F11 会空按一次
    val windowController = PlatformServiceProviders.getOrNull()?.window
    var fullscreen by remember(currentRoute) {
        mutableStateOf(
            windowController?.appliesPolicyFullscreen == true &&
                currentRoute?.let { WindowPolicies.forRoute(it).fullscreen } == true
        )
    }

    AppShortcutHandler(GlobalShortcuts.all) { shortcut ->
        when (shortcut) {
            GlobalShortcuts.Search ->
                if (currentRoute !is AppRoute.Search) navigator.push(AppRoute.Search())

            GlobalShortcuts.Settings -> navigator.push(AppRoute.MyConfig)
            GlobalShortcuts.Close -> performBack(navigator)
            GlobalShortcuts.Quit -> capabilities.exitApplication()
            GlobalShortcuts.Refresh -> navigator.refreshCurrent()
            GlobalShortcuts.Fullscreen -> {
                fullscreen = !fullscreen
                windowController?.setFullscreen(fullscreen)
            }
        }
    }
}
