package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

/**
 * [platformStatusBarPadding] 的 iOS actual: 接入 Compose Multiplatform WindowInsets
 * (内部映射到 iOS safeAreaInsets), 顶栏让位状态栏。
 *
 * 替代原 stub `this`; 与 Android 端 `Modifier.statusBarsPadding()` 实现对齐。
 * 注: 不能用 `WindowInsets.statusBars.asPaddingValues()`, 因本函数非 @Composable
 * (expect 见 sharedUiMain), 而 asPaddingValues 是 @Composable 扩展。
 * `statusBarsPadding()` 内部走 `windowInsetsPadding(WindowInsets.statusBars)`,
 * 非 @Composable, 在 iOS 上由 CMP 映射到 UIView.safeAreaInsets.top。
 */
actual fun Modifier.platformStatusBarPadding(): Modifier = this.statusBarsPadding()

/**
 * [rememberNavigationBarPaddingValues] 的 iOS actual: 接入 WindowInsets.navigationBars
 * (映射到 iOS safeAreaInsets.bottom), 底栏让位 home indicator。
 *
 * 替代原 stub `PaddingValues(0.dp)`; 与 Android 端行为对齐。
 */
@Composable
actual fun rememberNavigationBarPaddingValues(): PaddingValues =
    WindowInsets.navigationBars.asPaddingValues()

// iOS 无状态栏/导航栏显隐动画 (安全区域静态): 恒不隐藏; 固定高度 = 首次组合采样
// (不订阅 insets 流, 与 Android 事件化语义一致——动画期间无逐帧跟随需求)
@Composable
actual fun rememberStatusBarHidden(): Boolean = false

@Composable
actual fun rememberNavigationBarHidden(): Boolean = false

@Composable
actual fun rememberFixedStatusBarHeightPx(): Int {
    val density = LocalDensity.current
    return WindowInsets.statusBars.getTop(density)
}

// iOS 状态栏高度静态 (无显隐动画), 直接取当前值即可
@Composable
actual fun rememberVisibleStatusBarHeightPx(): Int {
    val density = LocalDensity.current
    return WindowInsets.statusBars.getTop(density)
}

@Composable
actual fun rememberFixedNavigationBarHeightPx(): Int {
    val density = LocalDensity.current
    return WindowInsets.navigationBars.getBottom(density)
}
