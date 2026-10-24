package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/**
 * [rememberMandatoryGestureBottomPx] 的 iOS / 鸿蒙 actual: 底部系统手势区
 * (home indicator / 手势导航条避让区) 高度, 与正文避让同源取
 * [WindowInsets.navigationBars] (见 StatusBarsPadding.native.kt)。
 *
 * 对照 Android [WindowInsets.mandatorySystemGestures] 语义: 手势带内触摸不参与
 * 阅读手势 (ReadViewComposable 分发层入口拦截), 防 home 手势上划误触翻页。
 */
@Composable
actual fun rememberMandatoryGestureBottomPx(): Int =
    WindowInsets.navigationBars.getBottom(LocalDensity.current)
