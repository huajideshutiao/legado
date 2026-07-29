package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [PlatformBackHandler] 的桌面 JVM actual: no-op。
 * 桌面端 ESC/Backspace 返回键由 [io.legado.app.ui.root.LegadoApp] 的
 * `handleBackKey` Modifier 统一拦截 (navigator.pop), 无系统返回键概念。
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op: 桌面端无系统返回键
}
