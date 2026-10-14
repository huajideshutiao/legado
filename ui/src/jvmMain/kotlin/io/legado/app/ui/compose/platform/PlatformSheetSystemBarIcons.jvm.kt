package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * No-op: 桌面端弹层是主窗口内的 ComposeSceneLayer, 无系统栏可设。
 */
@Composable
actual fun PlatformSheetSystemBarIcons(
    lightStatusBarIcons: Boolean?,
    lightNavigationBarIcons: Boolean,
) {
}
