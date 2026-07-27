package io.legado.desktop.ui.component

import androidx.compose.runtime.Composable
import io.legado.app.ui.widget.dialog.PhotoViewDialog

/**
 * 桌面端图片大图查看 Dialog (薄壳: 消费 sharedUiMain [PhotoViewDialog]), 详情页/段评等共用。
 * 加载走 ImageBitmapLoader jvm actual (OkHttp + ImageIO), 手势走共享 zoomable。
 *
 * @param src 图片路径 (本地路径 / file:// / http(s)://)
 * @param onDismiss 关闭回调
 */
@Composable
fun DesktopPhotoDialog(src: String, onDismiss: () -> Unit) {
    PhotoViewDialog(src = src, onDismiss = onDismiss)
}
