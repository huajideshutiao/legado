package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [rememberImeHiding] 的桌面 JVM actual:
 * 桌面端无软键盘/IME 动画概念, 恒 false (imeDismissPadding 退化为
 * windowInsetsPadding(WindowInsets.ime) = 恒 0 padding, 天然 no-op)。
 */
@Composable
actual fun rememberImeHiding(): Boolean = false

/**
 * [rememberImeAnimating] 的桌面 JVM actual: 无软键盘/IME 动画概念, 恒 false。
 */
@Composable
actual fun rememberImeAnimating(): Boolean = false

/**
 * [shouldConsumeImeInsets] 的桌面 JVM actual: 无软键盘/窗口收缩概念, 恒 false
 * (imeDismissPadding 退化为 no-op)。
 */
actual fun shouldConsumeImeInsets(): Boolean = false


/**
 * [rememberImeVisible] 的非 Android actual: 无软键盘概念, 恒 false。
 */
@Composable
actual fun rememberImeVisible(): Boolean = false
