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
 * [rememberImeVisible] 的桌面 JVM actual: 恒 true。
 *
 * 有意偏离原版 (原版仅软键盘弹出时显示帮助栏): 桌面端无软键盘/IME 概念, 恒 true
 * 使 [io.legado.app.ui.compose.component.code.KeyboardToolbar] 常驻显示, 保留
 * 查找替换/撤销重做/辅助键入口。其余调用点 (navBottom 判断/bringIntoView 类滚动)
 * 在桌面端不受影响: navigationBars inset 恒 0 结果不变, 滚动请求幂等 no-op。
 */
@Composable
actual fun rememberImeVisible(): Boolean = true
