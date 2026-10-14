package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * No-op: 桌面端弹层是主窗口内的 ComposeSceneLayer, 无系统栏可透明; 底层压暗由对话框自带的
 * 0.6 蒙层承担, 窗口加暗标志不适用。
 */
@Composable
actual fun PlatformSheetWindowStyle() {
}
