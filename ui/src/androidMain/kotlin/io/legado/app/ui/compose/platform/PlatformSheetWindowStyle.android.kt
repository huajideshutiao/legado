package io.legado.app.ui.compose.platform

import android.graphics.Color
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Android actual: 弹层窗口的系统栏透明 + 清掉主题自带暗化 (暗度改由
 * [io.legado.app.ui.compose.platform.PlatformDialogDim] 统一补, 见 expect 注释)。
 */
@Composable
actual fun PlatformSheetWindowStyle() {
    val view = LocalView.current
    DisposableEffect(view) {
        @Suppress("DEPRECATION")
        view.findDialogWindow()?.let {
            it.statusBarColor = Color.TRANSPARENT
            it.navigationBarColor = Color.TRANSPARENT
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.setDimAmount(0f)
        }
        onDispose {}
    }
}
