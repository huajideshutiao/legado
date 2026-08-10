package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/**
 * [rememberImeHiding] 的 Android actual。
 *
 * foundation-layout 的 imeAnimationSource / imeAnimationTarget (1.10.1 存在, 均为
 * @ExperimentalLayoutApi @Composable getter) 暴露 IME 动画的起始值/目标值
 * (见 WindowInsets.android.kt 的 InsetsListener: onApplyWindowInsets 无条件更新 target,
 * 非动画期间才更新 source; onEnd 后两者收敛为最终值):
 * - 无动画时 source == target (均为当前 ime 值);
 * - 收起动画期间 source (动画前键盘高) > target (0) → true, 且该状态在动画第一帧即成立
 *   (hide() 后系统立即带目标值回调 onApplyWindowInsets);
 * - 弹出动画期间 source (0) < target (键盘高) → false (padding 继续跟随 ime)。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun rememberImeHiding(): Boolean {
    val density = LocalDensity.current
    val source = WindowInsets.imeAnimationSource.getBottom(density)
    val target = WindowInsets.imeAnimationTarget.getBottom(density)
    return source > target
}
