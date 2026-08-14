package io.legado.app.ui.compose.platform

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * [platformStatusBarPadding] 的 Android actual: 直接委托 `Modifier.statusBarsPadding()`。
 */
actual fun Modifier.platformStatusBarPadding(): Modifier = this.statusBarsPadding()

/**
 * [platformNavigationBarPadding] 的 Android actual: 委托 `Modifier.navigationBarsPadding()`
 * (逐帧跟随, 供菜单底栏等浮层避让手势导航栏)。
 */
actual fun Modifier.platformNavigationBarPadding(): Modifier = this.navigationBarsPadding()

/**
 * [rememberNavigationBarPaddingValues] 的 Android actual:
 * 走 `WindowInsets.navigationBars.asPaddingValues()`, 避让手势导航栏。
 */
@Composable
actual fun rememberNavigationBarPaddingValues(): PaddingValues =
    WindowInsets.navigationBars.asPaddingValues()

// ---- 状态栏/导航栏显隐事件化 actual ----
// 显隐动画期间 insets 每帧变化: 读取移到布局监听侧, 只在"可见/不可见"翻转时写回布尔,
// 动画期间布尔恒定 (同 rememberImeVisible 模式)。

// 注意: 不用 Compose 的 WindowInsets.statusBars (@Composable getter, 非组合上下文不可调用,
// 且组合期读取订阅 insets 流), 改用 Android 平台 API ViewCompat —— 非 @Composable 可调用;
// 高度走 getInsetsIgnoringVisibility 缓存采样 (见 rememberVisibleStatusBarHeightPx),
// 不随布局事件逐帧重采 —— 显隐动画期间高度恒定, 只有布尔在翻转时更新一次
// (事件化语义, 对齐原版占位 View 配置驱动)。

@Composable
actual fun rememberStatusBarHidden(): Boolean {
    val view = LocalView.current
    var hidden by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val insets = ViewCompat.getRootWindowInsets(view)
            hidden = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) != true
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return hidden
}

@Composable
actual fun rememberNavigationBarHidden(): Boolean {
    val view = LocalView.current
    var hidden by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val insets = ViewCompat.getRootWindowInsets(view)
            hidden = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) != true
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return hidden
}

// 状态栏/导航栏可见高度进程级缓存: 采样走 getInsetsIgnoringVisibility —— 隐藏/显隐动画
// 期间也能取到真实高度, 消除"冷启动直达沉浸式页 → 缓存为 0"与"沉浸式内旋转 → 缓存陈旧"
// 两个问题; 仅在配置变化 (横竖屏/多窗口等) 时经组合重采, 不订阅 insets 流 (非逐帧)。
private var cachedVisibleStatusBarHeightPx = 0
private var cachedVisibleNavigationBarHeightPx = 0

/**
 * 状态栏可见时的高度 px: 走 getInsetsIgnoringVisibility 采样真实状态栏高度
 * (隐藏/显隐动画期间同样有效), 配置变化时重采; 供事件化 padding 翻转时一次取高。
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
 * 导航栏可见时的高度 px: 同 [rememberVisibleStatusBarHeightPx] 语义 (bottom)。
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
