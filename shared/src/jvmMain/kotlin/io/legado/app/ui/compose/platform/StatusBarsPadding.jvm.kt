package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * [platformStatusBarPadding] 的桌面 JVM actual: 桌面端无系统状态栏, 返回 this。
 */
actual fun Modifier.platformStatusBarPadding(): Modifier = this

/**
 * [rememberNavigationBarPaddingValues] 的桌面 JVM actual:
 * 桌面端无系统导航栏, 返回 `PaddingValues(0)`。
 */
@Composable
actual fun rememberNavigationBarPaddingValues(): PaddingValues = PaddingValues(0.dp)

// 桌面端无系统状态栏/导航栏显隐概念: 恒不隐藏、恒 0 高
@Composable
actual fun rememberStatusBarHidden(): Boolean = false

@Composable
actual fun rememberNavigationBarHidden(): Boolean = false

@Composable
actual fun rememberFixedStatusBarHeightPx(): Int = 0

@Composable
actual fun rememberVisibleStatusBarHeightPx(): Int = 0

@Composable
actual fun rememberFixedNavigationBarHeightPx(): Int = 0
