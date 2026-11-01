package io.legado.app.ui.compose.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.PopupProperties

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

/**
 * [textMenuPopupProperties] 的 skiko 三端 actual (Desktop/iOS/鸿蒙)。
 *
 * skiko 的 PopupProperties 默认 usePlatformInsets = true：框架会把平台安全区从 provider
 * 收到的 anchorBounds/windowSize 里扣除、再在返回位置时加回。本菜单的方向/限高/落点
 * 统一使用 LocalWindowInfo 全窗 + WindowInsets.safeDrawing 的可用区真值，需要 provider
 * 输入就是全窗坐标，故关闭该缩进。构造器在本端需 OptIn 的目标上标了
 * @ExperimentalComposeUiApi，故函数级 opt-in。
 */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun textMenuPopupProperties(): PopupProperties = PopupProperties(
    focusable = false,
    clippingEnabled = false,
    usePlatformInsets = false,
)
