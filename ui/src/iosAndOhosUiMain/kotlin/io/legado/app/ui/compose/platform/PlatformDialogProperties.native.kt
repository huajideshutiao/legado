// usePlatformInsets 在 CPF CMP 1.9.2 仍带 @ExperimentalComposeUiApi (弹层窗口是否把安全区
// 从内容里让出去); 本文件只参与 iOS/鸿蒙编译, 故在此文件级开 opt-in。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

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
 * iOS / 鸿蒙弹层 actual: usePlatformInsets = false 让窗口内容铺满整窗 (skiko 默认会把安全区从内容里
 * 让出去, 面板顶就够不到刘海/状态栏那一条), 顶部与底部安全区避让改由面板容器补; 键盘仍由窗口按
 * ime 自行避让 (useSoftwareKeyboardInset 保持默认)。底层压暗用对话框自带的 0.6 蒙层
 * (scrimColor 保持默认), 与居中对话框同一套。
 */
actual fun platformSheetDialogProperties(): DialogProperties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = true,
    usePlatformDefaultWidth = false,
    usePlatformInsets = false,
)

/**
 * iOS / 鸿蒙: ime ∪ navigationBars (逐边 max)。iOS 的 navigationBars 即底部安全区
 * (home 指示条), 键盘弹起时 ime 更大、收起时安全区兜底, 面板不被指示条压住。
 */
@Composable
actual fun bottomSheetBottomInsets(): WindowInsets =
    WindowInsets.ime.union(WindowInsets.navigationBars)
