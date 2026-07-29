package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [PlatformBackHandler] 的 iOS actual: no-op。
 * iOS 无系统返回键, 页面返回由 NavigationStack 滑动手势承载 (非 Compose 层拦截)。
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op: iOS 无系统返回键
}
