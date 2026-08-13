package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/**
 * [rememberImeHiding] 的 iOS actual:
 * iOS 无软键盘 inset/IME 动画概念, 恒 false (imeDismissPadding 天然 no-op)。
 */
@Composable
actual fun rememberImeHiding(): Boolean = false

/**
 * [rememberImeAnimating] 的 iOS actual: 无软键盘/IME 动画概念, 恒 false。
 */
@Composable
actual fun rememberImeAnimating(): Boolean = false

/**
 * [rememberImeTargetBottomPx] 的 iOS actual: 无软键盘 inset, 恒 0
 * (imeDismissPadding 退化为 no-op)。
 */
@Composable
actual fun rememberImeTargetBottomPx(): Int {
    val density = LocalDensity.current
    return WindowInsets.ime.getBottom(density)
}


/**
 * [rememberImeVisible] 的非 Android actual: 无软键盘概念, 恒 false。
 */
@Composable
actual fun rememberImeVisible(): Boolean = false
