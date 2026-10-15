package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [ProvidePlatformImeEditCommands] 的桌面/iOS/鸿蒙 actual: 直接透传。
 * 三端输入法在应用内, 编辑命令不经输入连接, 无此通道。
 */
@Composable
internal actual fun ProvidePlatformImeEditCommands(content: @Composable () -> Unit) {
    content()
}
