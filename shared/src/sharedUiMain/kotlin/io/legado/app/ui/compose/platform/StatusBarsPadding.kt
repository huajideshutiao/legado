package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 跨平台状态栏沉浸 padding。
 *
 * - Android: 走 [androidx.compose.foundation.layout.statusBarsPadding]
 * - 桌面 JVM / iOS / 鸿蒙: 无系统状态栏概念, 返回 this (无 padding)
 *
 * commonMain 侧的 Composable (如 AppTitleBar) 通过本函数获取状态栏 padding,
 * 避免 commonMain 直接依赖 Android 专属的 `Modifier.statusBarsPadding()`。
 */
expect fun Modifier.platformStatusBarPadding(): Modifier

/**
 * 跨平台导航栏 padding (返回 PaddingValues, 用于 LazyColumn contentPadding 等)。
 *
 * - Android: 走 `WindowInsets.navigationBars.asPaddingValues()` (避让手势导航栏)
 * - 桌面 JVM / iOS / 鸿蒙: 无系统导航栏概念, 返回 `PaddingValues(0)` (无 padding)
 *
 * commonMain 侧的 Composable (如 PreferenceScreen) 通过本函数获取默认
 * contentPadding, 避免 commonMain 直接依赖 Android 专属的
 * `WindowInsets.navigationBars.asPaddingValues()`。
 */
@Composable
expect fun rememberNavigationBarPaddingValues(): PaddingValues
