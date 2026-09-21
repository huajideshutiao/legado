package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [PlatformBackHandler] 的 Android actual:
 * 委托 `androidx.activity.compose.BackHandler`, 拦截系统返回键/返回手势。
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}
