package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [rememberMandatoryGestureBottomPx] 的鸿蒙 actual:
 * 鸿蒙无"强制系统手势区"概念，底部导航条避让已由 `WindowInsets.navigationBars` 覆盖，
 * 恒 0（同 iOS 侧处理）。
 */
@Composable
actual fun rememberMandatoryGestureBottomPx(): Int = 0
