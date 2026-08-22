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
