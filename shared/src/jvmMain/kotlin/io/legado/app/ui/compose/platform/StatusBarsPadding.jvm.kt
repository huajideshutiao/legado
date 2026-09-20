package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

// 桌面端无系统状态栏/导航栏概念: 恒 0 高
@Composable
actual fun rememberVisibleStatusBarHeightPx(): Int = 0

@Composable
actual fun rememberVisibleNavigationBarHeightPx(): Int = 0
