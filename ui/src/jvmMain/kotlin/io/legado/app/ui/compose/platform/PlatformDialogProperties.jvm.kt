package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * Desktop actual: skiko 的 DialogProperties 无 decorFitsSystemWindows 概念
 * (对话框是主窗口内层, 无系统栏/IME 避让语义), 忽略该参数, 走 common 3 参构造器。
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
 * Desktop 弹层 actual: usePlatformInsets = false 让窗口内容铺满整窗 (skiko 默认会把安全区/
 * 系统栏从内容里让出去), 底部避让改由面板容器补; 键盘仍由窗口按 ime 自行避让
 * (useSoftwareKeyboardInset 保持默认)。底层压暗用对话框自带的 0.6 蒙层 (scrimColor 保持默认),
 * 与居中对话框同一套。
 */
actual fun platformSheetDialogProperties(): DialogProperties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = true,
    usePlatformDefaultWidth = false,
    usePlatformInsets = false,
)

/** Desktop: 无导航栏 inset 概念 (恒 0), 仅 ime, 等价现版 imePadding。 */
@Composable
actual fun bottomSheetBottomInsets(): WindowInsets = WindowInsets.ime
