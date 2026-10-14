package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * No-op: iOS / 鸿蒙弹层无窗口加暗标志可设, 底层压暗由对话框自带的 0.6 蒙层承担; 底部安全区
 * 由平台自管。
 */
@Composable
actual fun PlatformSheetWindowStyle() {
}
