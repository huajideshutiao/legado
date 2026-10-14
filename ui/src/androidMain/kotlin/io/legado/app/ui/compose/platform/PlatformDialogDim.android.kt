package io.legado.app.ui.compose.platform

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView

/**
 * Android: 平台 Dialog 窗口主题无暗化, 补 FLAG_DIM_BEHIND + dimAmount 0.6
 * (对齐桌面/iOS 自带 0.6 scrim 与原版 AppCompat backgroundDimAmount=0.6)。
 */
@Composable
actual fun PlatformDialogDim() {
    val view = LocalView.current
    LaunchedEffect(Unit) {
        view.findDialogWindow()?.let {
            it.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.setDimAmount(0.6f)
        }
    }
}
