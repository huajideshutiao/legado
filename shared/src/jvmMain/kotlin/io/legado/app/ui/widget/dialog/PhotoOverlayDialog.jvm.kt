package io.legado.app.ui.widget.dialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.DialogProperties
import io.legado.app.ui.compose.component.AppDialog

/**
 * Desktop actual: 与 iOS 同为 skiko Dialog 实现, usePlatformInsets=false。
 * 桌面无系统栏, 该配置无副作用 (窗口内对话框层铺满当前窗口)。
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
