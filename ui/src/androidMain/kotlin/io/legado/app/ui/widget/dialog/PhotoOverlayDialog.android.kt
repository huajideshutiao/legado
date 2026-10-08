package io.legado.app.ui.widget.dialog

import android.graphics.Color
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.platform.platformDialogProperties

/**
 * Android actual: decorFitsSystemWindows=false 让对话框窗口延伸到状态栏/导航栏之下, 同时把
 * 系统栏设为透明、图标置浅色, 保证黑底大图铺满整屏。
 *
 * 平台窗口暗化必须清掉 (decorFitsSystemWindows=false 走的 FloatingDialogWindowTheme 带
 * backgroundDimEnabled): 暗度只由查看器自绘蒙版一处定义, 平台叠加会明显过暗, 也与主窗口
 * 内层路径 (参与共享转场时) 不一致。
 */
@Composable
internal actual fun PlatformPhotoOverlayDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismissRequest,
        properties = platformDialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
        dim = false,
        animate = false,
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            // LocalView 是 DialogLayout 内的 AndroidComposeView, 沿父链找到实现
            // DialogWindowProvider 的 DialogLayout 以拿到对话框 Window。
            var window: Window? = null
            var v: View? = view
            while (v != null && window == null) {
                window = (v as? DialogWindowProvider)?.window
                v = v.parent as? View
            }
            @Suppress("DEPRECATION")
            window?.let {
                it.statusBarColor = Color.TRANSPARENT
                it.navigationBarColor = Color.TRANSPARENT
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                it.setDimAmount(0f)
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
            onDispose {}
        }
        content()
    }
}
