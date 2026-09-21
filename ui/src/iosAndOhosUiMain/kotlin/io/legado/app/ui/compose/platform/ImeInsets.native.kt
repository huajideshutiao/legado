package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * iOS / 鸿蒙共用的 [rememberImeAnimating] actual: 恒 false。
 *
 * 两端都有 ime inset, 但 imeAnimationSource/imeAnimationTarget 仅 Android 有 (已核 klib
 * ABI), 无从判定动画进行中; 逐帧比对要自建帧循环, 代价大于收益 (只用于抑制动画期的
 * 高亮窗口重建), 暂不做。
 *
 * 另一个 expect 两端行为不同, 仍留在各自 leaf:
 * - [rememberImeVisible]: iOS 监听 UIKit 键盘通知 / 鸿蒙读 CPF 的 ime inset
 */
@Composable
actual fun rememberImeAnimating(): Boolean = false
