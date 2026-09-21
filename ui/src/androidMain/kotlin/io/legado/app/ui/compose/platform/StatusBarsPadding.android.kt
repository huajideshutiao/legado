package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

// 状态栏/导航栏应有高度进程级缓存: 采样走 getInsetsIgnoringVisibility —— 隐藏/显隐动画
// 期间也能取到真实高度, 消除"冷启动直达沉浸式页 → 缓存为 0"与"沉浸式内旋转 → 缓存陈旧"
// 两个问题; 仅在配置变化 (横竖屏/多窗口等) 时经组合重采, 不订阅 insets 流 (非逐帧)。
private var cachedVisibleStatusBarHeightPx = 0
private var cachedVisibleNavigationBarHeightPx = 0

/**
 * 状态栏应有高度 px: 走 getInsetsIgnoringVisibility 采样真实状态栏高度
 * (隐藏/显隐动画期间同样有效), 配置变化时重采。
 */
@Composable
actual fun rememberVisibleStatusBarHeightPx(): Int {
    val view = LocalView.current
    val config = LocalConfiguration.current
    return remember(config) {
        val h = ViewCompat.getRootWindowInsets(view)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        if (h > 0) {
            cachedVisibleStatusBarHeightPx = h
            h
        } else {
            cachedVisibleStatusBarHeightPx
        }
    }
}

/**
 * 导航栏应有高度 px: 同 [rememberVisibleStatusBarHeightPx] 语义 (bottom)。
 */
@Composable
actual fun rememberVisibleNavigationBarHeightPx(): Int {
    val view = LocalView.current
    val config = LocalConfiguration.current
    return remember(config) {
        val h = ViewCompat.getRootWindowInsets(view)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        if (h > 0) {
            cachedVisibleNavigationBarHeightPx = h
            h
        } else {
            cachedVisibleNavigationBarHeightPx
        }
    }
}
