package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Android actual: 按面板底色覆盖弹层窗口的系统栏图标明暗; 状态栏在面板未接管时写回
 * 进入弹层时读到的窗口取值 (弹层主题给的默认)。
 */
@Composable
actual fun PlatformSheetSystemBarIcons(
    lightStatusBarIcons: Boolean?,
    lightNavigationBarIcons: Boolean,
) {
    val view = LocalView.current
    val window = remember(view) { view.findDialogWindow() }
    // 窗口既有取值: 弹层主题给的默认, 面板未接管状态栏区域时恢复它
    var windowStatusBarDefault by remember(window) { mutableStateOf<Boolean?>(null) }
    DisposableEffect(window) {
        windowStatusBarDefault = window?.let {
            WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars
        }
        onDispose {}
    }
    LaunchedEffect(window, windowStatusBarDefault, lightStatusBarIcons, lightNavigationBarIcons) {
        val targetWindow = window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(targetWindow, targetWindow.decorView)
        val statusBarTarget = lightStatusBarIcons ?: windowStatusBarDefault
        if (statusBarTarget != null) controller.isAppearanceLightStatusBars = statusBarTarget
        controller.isAppearanceLightNavigationBars = lightNavigationBarIcons
    }
}
