package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

// iOS / 鸿蒙: 安全区域静态 (无系统栏显隐动画), 直接取当前值
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
