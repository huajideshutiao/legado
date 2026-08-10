package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * 鸿蒙 actual: CPF compose-ohos fork 的 DialogProperties 无 decorFitsSystemWindows
 * (common 3 参), 忽略该参数; 底部避让仅 ime, 等价现版 imePadding。
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

@Composable
actual fun bottomSheetBottomInsets(): WindowInsets = WindowInsets.ime
