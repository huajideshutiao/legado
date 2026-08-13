package io.legado.app.ui.compose.platform

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * [rememberImeVisible] 的 Android actual: ViewTreeObserver 布局监听 + ViewCompat 读 ime
 * 可见性布尔。布局事件在键盘动画期间会频繁触发, 但 ime 可见性布尔在动画期间不变
 * (弹出即可见、收起即不可见), 相同值写入 state 不触发重组 —— 只在翻转时更新一次。
 * 不读 WindowInsets.ime 数值 (@Composable getter 无法在非组合上下文调用, 且逐帧变化)。
 */
@Composable
actual fun rememberImeVisible(): Boolean {
    val view = LocalView.current
    var imeVisible by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val insets = ViewCompat.getRootWindowInsets(view)
            imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }
    return imeVisible
}

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

/**
 * [rememberImeAnimating] 的 Android actual: 动画期间 source (动画前冻结值) 与
 * target (动画第一帧即置为最终目标值) 不相等, 动画结束两者收敛 —— 只在动画边界翻转。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun rememberImeAnimating(): Boolean {
    val density = LocalDensity.current
    return WindowInsets.imeAnimationSource.getBottom(density) !=
        WindowInsets.imeAnimationTarget.getBottom(density)
}

/**
 * [rememberImeTargetBottomPx] 的 Android actual: 动画第一帧即置为最终目标高度,
 * 动画期间恒定; 无动画时 = 当前 ime 高度。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun rememberImeTargetBottomPx(): Int {
    val density = LocalDensity.current
    return WindowInsets.imeAnimationTarget.getBottom(density)
}
