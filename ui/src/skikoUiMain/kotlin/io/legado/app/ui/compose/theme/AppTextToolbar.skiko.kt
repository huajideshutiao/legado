package io.legado.app.ui.compose.theme

import androidx.compose.runtime.Composable

/**
 * [ProvidePlatformTextMenu] 的桌面/iOS/鸿蒙 actual: 直接透传。
 *
 * 三端的 foundation 里 `ComposeFoundationFlags.isNewContextMenuEnabled` 为 false, 选区菜单仍走
 * [androidx.compose.ui.platform.LocalTextToolbar]; 新通道的 skiko/native
 * ProvideDefaultPlatformTextContextMenuProviders 目前还是空实现 (CMP-7819), 接了也不会被调用。
 */
@Composable
internal actual fun ProvidePlatformTextMenu(
    state: AppTextMenuState,
    content: @Composable () -> Unit,
) {
    content()
}
