package io.legado.app.ui.compose.platform

import android.view.ViewParent
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Android: 平台 Dialog 窗口主题无暗化, 补 FLAG_DIM_BEHIND + dimAmount 0.6
 * (对齐桌面/iOS 自带 0.6 scrim 与原版 AppCompat backgroundDimAmount=0.6)。
 * 从内容 View 上溯找 DialogLayout (DialogWindowProvider) 取窗口。
 */
@Composable
actual fun PlatformDialogDim() {
    val view = LocalView.current
    LaunchedEffect(Unit) {
        // 从内容 View 上溯找实现 DialogWindowProvider 的窗口宿主 (Dialog 的 decor view)
        var current: ViewParent? = view.parent
        var provider: DialogWindowProvider? = null
        while (current != null) {
            provider = current as? DialogWindowProvider
            if (provider != null) break
            current = current.parent
        }
        val window = provider?.window
        window?.let {
            it.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.setDimAmount(0.6f)
        }
    }
}
