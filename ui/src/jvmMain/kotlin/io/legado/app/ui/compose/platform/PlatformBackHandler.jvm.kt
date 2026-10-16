package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

/**
 * [PlatformBackHandler] 的桌面 JVM actual: no-op。
 * 桌面端 ESC/Backspace 返回键由 [io.legado.app.ui.root.LegadoApp] 的
 * `handleBackKey` Modifier 统一拦截 (navigator.pop), 无系统返回键概念。
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op: 桌面端无系统返回键
}

/**
 * [PlatformPredictiveBackHandler] 的桌面 JVM actual: no-op。
 * 桌面端无返回手势 (ESC 无进度可跟手), 跟手转场只存在于有该通道的端;
 * [PlatformPredictiveBackHandler] 的 onBackClick 出口在本端恒不被调用。
 */
@Composable
actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onGesture: suspend (gesture: Flow<Float>) -> Boolean,
    onBackClick: () -> Unit,
) {
    // No-op: 桌面端无返回手势
}
