package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [rememberImeAnimating] 的桌面 JVM actual: 无软键盘/IME 动画概念, 恒 false。
 */
@Composable
actual fun rememberImeAnimating(): Boolean = false

/**
 * [rememberImeVisible] 的桌面 JVM actual: 恒 true。
 *
 * 有意偏离原版 (原版仅软键盘弹出时显示帮助栏): 桌面端无软键盘/IME 概念, 恒 true
 * 使 [io.legado.app.ui.compose.component.code.KeyboardToolbar] 常驻显示, 保留
 * 查找替换/撤销重做/辅助键入口。
 */
@Composable
actual fun rememberImeVisible(): Boolean = true
