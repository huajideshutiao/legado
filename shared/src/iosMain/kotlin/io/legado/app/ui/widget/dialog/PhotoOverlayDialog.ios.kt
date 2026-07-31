package io.legado.app.ui.widget.dialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import io.legado.app.ui.compose.component.AppDialog
import androidx.compose.ui.window.DialogProperties

/**
 * iOS actual: CMP 的 Dialog 走 skiko 实现, usePlatformInsets=false 关闭安全区裁剪,
 * 让黑色背景延伸到状态栏/刘海/底部横条区域, 完整盖住整屏。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun PlatformPhotoOverlayDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            usePlatformInsets = false,
        ),
        content = content,
    )
}
