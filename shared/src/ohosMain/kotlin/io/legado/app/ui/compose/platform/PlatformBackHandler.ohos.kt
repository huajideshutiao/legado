package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [PlatformBackHandler] 的鸿蒙 actual: no-op。
 * 鸿蒙端系统返回手势暂未接入 Compose 拦截层 (待 napi 桥接 BackHandler),
 * 当前与桌面/iOS 一致 no-op。
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op: 鸿蒙端系统返回手势暂未接入
}
