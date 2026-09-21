package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * Android actual: 走 8 参构造器显式传 decorFitsSystemWindows (3 参构造器硬编码 true,
 * 见 androidx ui-android AndroidDialog.android.kt:150)。
 */
actual fun platformDialogProperties(
    dismissOnBackPress: Boolean,
    dismissOnClickOutside: Boolean,
    decorFitsSystemWindows: Boolean,
): DialogProperties = DialogProperties(
    dismissOnBackPress = dismissOnBackPress,
    dismissOnClickOutside = dismissOnClickOutside,
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = decorFitsSystemWindows,
)

/**
 * Android: ime ∪ navigationBars。decor=false 后键盘弹出时两 insets 并存
 * (键盘从导航栏上方出现, 手势条仍可见), max 语义 = 面板底边顶在键盘顶;
 * 键盘收起后 ime=0, 剩导航栏避让, 面板不被 3 键导航黑条压住。
 */
@Composable
actual fun bottomSheetBottomInsets(): WindowInsets =
    WindowInsets.ime.union(WindowInsets.navigationBars)
