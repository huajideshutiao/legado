package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * No-op: iOS / 鸿蒙的 CMP Dialog 自带 scrim (skiko DefaultScrimOpacity 0.6)。
 */
@Composable
actual fun PlatformDialogDim() {
}
