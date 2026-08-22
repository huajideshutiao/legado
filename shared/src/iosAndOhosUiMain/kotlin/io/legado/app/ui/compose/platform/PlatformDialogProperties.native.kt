package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * iOS / 鸿蒙 actual: 两端 DialogProperties 均解析 skiko 版 (与桌面同源), 无
 * decorFitsSystemWindows 概念, 忽略该参数, 走 common 3 参构造器。
 */
actual fun platformDialogProperties(
    dismissOnBackPress: Boolean,
    dismissOnClickOutside: Boolean,
    decorFitsSystemWindows: Boolean,
): DialogProperties = DialogProperties(
    dismissOnBackPress = dismissOnBackPress,
    dismissOnClickOutside = dismissOnClickOutside,
    usePlatformDefaultWidth = false,
)

/**
 * iOS / 鸿蒙: ime ∪ navigationBars (逐边 max)。iOS 的 navigationBars 即底部安全区
 * (home 指示条), 键盘弹起时 ime 更大、收起时安全区兜底, 面板不被指示条压住。
 */
@Composable
actual fun bottomSheetBottomInsets(): WindowInsets =
    WindowInsets.ime.union(WindowInsets.navigationBars)
