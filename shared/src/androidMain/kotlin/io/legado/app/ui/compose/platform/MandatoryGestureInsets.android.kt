package io.legado.app.ui.compose.platform

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/**
 * [rememberMandatoryGestureBottomPx] 的 Android actual:
 * 走 `WindowInsets.mandatorySystemGestures.getBottom()`（foundation-layout 1.11.4 存在，
 * getter 为 @Composable；内部经 WindowInsetsHolder 用 `getInsetsIgnoringVisibility` 读取，
 * 与原版 `rootWindowInsets.getInsetsIgnoringVisibility(Type.mandatorySystemGestures())`
 * 语义一致：手势条隐藏时也按占位算）。
 *
 * 不缓存：与 ReadViewComposable 读 statusBars/navigationBars 同风格，inset 变化
 * （旋转 / 三键与手势导航切换）随重组刷新。
 *
 * 对照原版 ReadView.onTouchEvent 的 SDK 版本门：API < 30（R）整段跳过，返回 0。
 */
@Composable
actual fun rememberMandatoryGestureBottomPx(): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
    return WindowInsets.mandatorySystemGestures.getBottom(LocalDensity.current)
}
