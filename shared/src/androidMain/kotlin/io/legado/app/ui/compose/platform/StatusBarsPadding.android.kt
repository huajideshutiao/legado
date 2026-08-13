package io.legado.app.ui.compose.platform

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * [rememberNavigationBarPaddingValues] 的 Android actual:
 * 走 `WindowInsets.navigationBars.asPaddingValues()`, 避让手势导航栏。
 */
@Composable
actual fun rememberNavigationBarPaddingValues(): PaddingValues =
    WindowInsets.navigationBars.asPaddingValues()

// ---- 状态栏/导航栏显隐事件化 actual ----
// 显隐动画期间 insets 每帧变化: 读取移到副作用侧 (snapshotFlow 协程内读 snapshot state),
// 只在"可见/不可见"翻转时写回, 动画期间布尔恒定 (同 rememberImeVisible 模式)。

// 注意: 不用 Compose 的 WindowInsets.statusBars (@Composable getter, 非组合上下文不可调用,
// 且组合期读取订阅 insets 流), 改用 Android 平台 API ViewCompat —— 非 @Composable 可调用,
// remember 初始化即取首帧值, 布局事件 (显隐动画期间频繁触发但布尔/高度不翻倍变化) 时重读,
// 相等值写 state 不触发重组 —— 只在显隐翻转时更新一次 (事件化语义, 对齐原版占位 View 配置驱动)。

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

// 状态栏可见高度进程级缓存: 设备使用期间状态栏高度恒定, 无需布局监听逐帧重读;
// 仅在配置变化 (横竖屏/多窗口等) 时经组合重采, 状态栏隐藏期间采样为 0 时
// 保留最近可见值 (供转场冻结: pop 回书架时动画开始前高度已为 0)
private var cachedVisibleStatusBarHeightPx = 0

/**
 * 状态栏可见时的高度 px: 全局缓存, 配置变化时重采; 隐藏期间返回最近可见值。
 */
@Composable
actual fun rememberVisibleStatusBarHeightPx(): Int {
    val view = LocalView.current
    val config = LocalConfiguration.current
    return remember(config) {
        val h = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        if (h > 0) {
            cachedVisibleStatusBarHeightPx = h
            h
        } else {
            cachedVisibleStatusBarHeightPx
        }
    }
}

/**
 * 固定高度采样: remember 初始化取首帧值 (非 @Composable API, 无订阅); 布局事件重读,
 * 横竖屏/导航模式切换等配置变化时经 DisposableEffect key 重启重采 (配置变化非动画期)。
 */
@Composable
actual fun rememberFixedStatusBarHeightPx(): Int {
    val view = LocalView.current
    val config = LocalConfiguration.current
    fun sample(): Int =
        ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
    var heightPx by remember {
        mutableIntStateOf(
            ViewCompat.getRootWindowInsets(view)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        )
    }
    DisposableEffect(view, config.orientation) {
        heightPx = sample()
        val listener = ViewTreeObserver.OnGlobalLayoutListener { heightPx = sample() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return heightPx
}

@Composable
actual fun rememberFixedNavigationBarHeightPx(): Int {
    val view = LocalView.current
    val config = LocalConfiguration.current
    fun sample(): Int =
        ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
    var heightPx by remember {
        mutableIntStateOf(
            ViewCompat.getRootWindowInsets(view)
                ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        )
    }
    DisposableEffect(view, config.orientation) {
        heightPx = sample()
        val listener = ViewTreeObserver.OnGlobalLayoutListener { heightPx = sample() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return heightPx
}
