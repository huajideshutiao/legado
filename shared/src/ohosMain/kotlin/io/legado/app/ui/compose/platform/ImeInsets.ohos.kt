package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [rememberImeHiding] 的鸿蒙 actual:
 * 鸿蒙无软键盘 inset/IME 动画概念, 恒 false (imeDismissPadding 天然 no-op)。
 */
@Composable
actual fun rememberImeHiding(): Boolean = false

/**
 * [rememberImeAnimating] 的鸿蒙 actual: 无软键盘/IME 动画概念, 恒 false。
 */
@Composable
actual fun rememberImeAnimating(): Boolean = false

/**
 * [shouldConsumeImeInsets] 的鸿蒙 actual: 无软键盘/窗口收缩概念, 恒 false
 * (imeDismissPadding 天然 no-op)。
 */
actual fun shouldConsumeImeInsets(): Boolean = false


/**
 * [rememberImeVisible] 的非 Android actual: 无软键盘概念, 恒 false。
 */
@Composable
actual fun rememberImeVisible(): Boolean = false
