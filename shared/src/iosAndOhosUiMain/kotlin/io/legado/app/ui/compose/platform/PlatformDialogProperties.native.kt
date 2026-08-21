package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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

/** 底部避让仅 ime (键盘 insets), 不涉系统栏, 等价现版 imePadding。 */
@Composable
actual fun bottomSheetBottomInsets(): WindowInsets = WindowInsets.ime
