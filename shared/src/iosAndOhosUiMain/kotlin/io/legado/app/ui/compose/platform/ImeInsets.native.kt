package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * iOS / 鸿蒙共用的 [rememberImeHiding] 与 [rememberImeAnimating] actual
 * (两端原实现逐字相同, 合并去重): 两端均无软键盘 inset/IME 动画概念, 恒 false
 * (imeDismissPadding 天然 no-op)。
 *
 * 同文件另两个 expect 两端行为不同, 仍留在各自 leaf:
 * - [shouldConsumeImeInsets]: iOS true (CMP 键盘不收缩窗口, ime insets 全量派发) /
 *   鸿蒙 false (CPF foundation-layout 无键盘代码)
 * - [rememberImeVisible]: iOS 监听 UIKit 键盘通知 / 鸿蒙恒 true
 */
@Composable
actual fun rememberImeHiding(): Boolean = false

@Composable
actual fun rememberImeAnimating(): Boolean = false
