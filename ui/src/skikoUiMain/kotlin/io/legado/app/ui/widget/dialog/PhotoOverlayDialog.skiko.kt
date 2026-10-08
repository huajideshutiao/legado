package io.legado.app.ui.widget.dialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties
import io.legado.app.ui.compose.component.AppDialog

/**
 * 桌面 / iOS / 鸿蒙 actual (三端共用 skiko Dialog 实现): usePlatformDefaultWidth=false +
 * usePlatformInsets=false 让满屏内容铺满当前窗口并延伸到状态栏/刘海/底部横条之下。
 *
 * 平台自带 scrim 置透明: 暗度只由查看器自绘蒙版一处定义, 与主窗口内层路径 (参与共享转场时)
 * 视觉一致, 两处叠加会明显过暗。不传 animateTransition (鸿蒙 fork 的 DialogProperties 无此
 * 参数), 进场无动画由 [AppDialog] 的 animate=false 分支承担。
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
            scrimColor = Color.Transparent,
        ),
        dim = false,
        animate = false,
        content = content,
    )
}
