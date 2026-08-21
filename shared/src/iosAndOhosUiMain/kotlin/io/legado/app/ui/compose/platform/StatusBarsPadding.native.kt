package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

/**
 * [platformStatusBarPadding] 的 iOS / 鸿蒙 actual: 接入 CMP WindowInsets
 * (由 CMP + skiko 映射到 iOS safeAreaInsets / 鸿蒙 avoidArea), 顶栏让位状态栏。
 *
 * 注: 不能用 `WindowInsets.statusBars.asPaddingValues()`, 因本函数非 @Composable
 * (expect 见 sharedUiMain), 而 asPaddingValues 是 @Composable 扩展;
 * `statusBarsPadding()` 内部走非 @Composable 的 `windowInsetsPadding`。
 * 鸿蒙 fork 未桥接真实 avoidArea 时退化为 0 padding (不劣于原 stub `this`)。
 */
actual fun Modifier.platformStatusBarPadding(): Modifier = this.statusBarsPadding()

/**
 * [platformNavigationBarPadding] 的 iOS / 鸿蒙 actual: 接入 WindowInsets.navigationBars,
 * 底栏让位 home indicator / 手势导航条。
 */
actual fun Modifier.platformNavigationBarPadding(): Modifier = this.navigationBarsPadding()

/**
 * [rememberNavigationBarPaddingValues] 的 iOS / 鸿蒙 actual: 同上, 以 PaddingValues 返回
 * (与 Android 端行为对齐; 未桥接真实值时为 `PaddingValues(0)`)。
 */
@Composable
actual fun rememberNavigationBarPaddingValues(): PaddingValues =
    WindowInsets.navigationBars.asPaddingValues()

// 两端均无状态栏/导航栏显隐动画 (安全区域静态): 恒不隐藏; 高度静态, 直接取当前值
// (不订阅 insets 流, 与 Android 事件化语义一致——无逐帧跟随需求)
@Composable
actual fun rememberStatusBarHidden(): Boolean = false

@Composable
actual fun rememberNavigationBarHidden(): Boolean = false

// 状态栏高度静态 (无显隐动画), 直接取当前值即可
@Composable
actual fun rememberVisibleStatusBarHeightPx(): Int {
    val density = LocalDensity.current
    return WindowInsets.statusBars.getTop(density)
}

@Composable
actual fun rememberVisibleNavigationBarHeightPx(): Int {
    val density = LocalDensity.current
    return WindowInsets.navigationBars.getBottom(density)
}
