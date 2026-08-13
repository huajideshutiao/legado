package io.legado.app.ui.compose.platform

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
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView

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
