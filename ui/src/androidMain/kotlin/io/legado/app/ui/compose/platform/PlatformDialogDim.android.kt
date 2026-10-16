package io.legado.app.ui.compose.platform

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Android: 平台 Dialog 窗口主题无暗化, 补 FLAG_DIM_BEHIND + dimAmount 0.6
 * (对齐桌面/iOS 自带 0.6 scrim 与原版 AppCompat backgroundDimAmount=0.6)。
 *
 * 用 [DisposableEffect] (组合期同步执行) 而非 LaunchedEffect: 后者排在组合之后的协程队列,
 * 窗口已经显示、下一帧才补上暗化, 表现为底层"先亮一帧再突然变暗"。
 */
@Composable
actual fun PlatformDialogDim() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.findDialogWindow()?.let {
            it.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.setDimAmount(0.6f)
        }
        onDispose {}
    }
}
