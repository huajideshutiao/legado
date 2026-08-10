package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [rememberImeHiding] 的 iOS actual:
 * iOS 无软键盘 inset/IME 动画概念, 恒 false (imeDismissPadding 天然 no-op)。
 */
@Composable
actual fun rememberImeHiding(): Boolean = false
