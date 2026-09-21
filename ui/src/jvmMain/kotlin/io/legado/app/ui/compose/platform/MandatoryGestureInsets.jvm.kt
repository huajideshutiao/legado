package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [rememberMandatoryGestureBottomPx] 的桌面 JVM actual:
 * 桌面端无"强制系统手势区"概念（无系统返回/回主页手势），恒 0，拦截旁路。
 */
@Composable
actual fun rememberMandatoryGestureBottomPx(): Int = 0
