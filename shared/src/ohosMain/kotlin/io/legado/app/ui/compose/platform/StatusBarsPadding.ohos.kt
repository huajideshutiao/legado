package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * [platformStatusBarPadding] 的鸿蒙 actual: 接入 Compose Multiplatform WindowInsets,
 * 顶栏让位系统状态栏。
 *
 * 与 iOS 端 [StatusBarsPadding.ios.kt] 实现对齐: 均走 `Modifier.statusBarsPadding()`
 * (内部 `windowInsetsPadding(WindowInsets.statusBars)`), 由 CMP + skiko 在 native 端
 * 映射到平台安全区域。
 *
 * 注: 不能用 `WindowInsets.statusBars.asPaddingValues()`, 因本函数非 @Composable
 * (expect 见 sharedUiMain), 而 asPaddingValues 是 @Composable 扩展。
 *
 * 若 fork 版未在 ohos 侧桥接真实 avoidArea, 本调用退化为 0 padding
 * (不劣于原 stub `this`), 后续可由 napi 桥接 `window.getWindowAvoidArea()` 补充。
 */
actual fun Modifier.platformStatusBarPadding(): Modifier = this.statusBarsPadding()

/**
 * [rememberNavigationBarPaddingValues] 的鸿蒙 actual: 接入 WindowInsets.navigationBars,
 * 底栏让位系统导航栏 (手势导航条)。
 *
 * 与 iOS / Android 端实现对齐。若 fork 版未桥接真实值, 返回 `PaddingValues(0)`。
 */
@Composable
actual fun rememberNavigationBarPaddingValues(): PaddingValues =
    WindowInsets.navigationBars.asPaddingValues()
