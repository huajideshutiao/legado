package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * No-op: iOS / 鸿蒙弹层是主窗口内的层, 系统栏图标由宿主视图控制器决定, 无弹层窗口可设。
 */
@Composable
actual fun PlatformSheetSystemBarIcons(
    lightStatusBarIcons: Boolean?,
    lightNavigationBarIcons: Boolean,
) {
}
