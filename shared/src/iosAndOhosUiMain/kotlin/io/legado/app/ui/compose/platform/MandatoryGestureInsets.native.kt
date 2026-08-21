package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [rememberMandatoryGestureBottomPx] 的 iOS / 鸿蒙 actual: 两端都无"强制系统手势区"概念,
 * 底部 home indicator / 导航条避让已由 `WindowInsets.navigationBars` 覆盖, 恒 0。
 */
@Composable
actual fun rememberMandatoryGestureBottomPx(): Int = 0
