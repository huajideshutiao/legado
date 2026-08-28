package io.legado.app.ui.widget.dialog

import android.graphics.Color
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.legado.app.ui.compose.component.AppDialog

/**
 * Android actual: decorFitsSystemWindows=false 让对话框窗口延伸到状态栏/导航栏之下,
 * 同时把系统栏设为透明、图标置浅色, 保证黑底大图背景完整盖住整屏。
 */
@Composable
internal actual fun PlatformPhotoOverlayDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
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
