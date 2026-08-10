package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [rememberMandatoryGestureBottomPx] 的 iOS actual:
 * iOS 无"强制系统手势区"概念——home indicator 的避让已由 safeArea bottom
 * （`WindowInsets.navigationBars`）覆盖，无独立的手势拦截带，恒 0。
 */
@Composable
actual fun rememberMandatoryGestureBottomPx(): Int = 0
