package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.image.isGifBytes
import io.legado.app.help.image.rememberAnimatedImageBitmap
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.zoomable
import io.legado.app.ui.compose.platform.rememberString

/**
 * 跨平台大图查看内容件 (对照 app 端 [io.legado.app.ui.widget.dialog.PhotoDialog] 的 Content)。
 *
 * 图片加载走 [ImageBitmapLoader] (commonMain 面, 各端 actual: jvm=OkHttp+ImageIO,
 * iOS=Coil3，鸿蒙=ArkUI 融合渲染平台图片管线；传 [bookSource] 时网络图自动带书源防盗链 header/cookie)。
 * 手势复用共享 [zoomable] (双指缩放/单指平移/双击循环/fling 惯性, E-Ink 自动降级)。
 *
 * GIF 动图: desktop 经 [rememberAnimatedImageBitmap] 使用 Skiko Codec 逐帧播放；
 * 其余格式与其他端仍走静态 [ImageBitmapLoader] 路径。
 *
 * app 端 PhotoDialog 不消费本件: 其加载链含章节缓存文件/EPUB ZIP/SVG/data URI/Coil
 * 磁盘缓存/默认封面兜底 (Android 专属), 且 androidMain 的 ImageBitmapLoader 暂为 stub。
 *
 * @param src 图片路径 (http(s):// / file:// / 绝对路径, 各端 actual 支持范围见 ImageBitmapLoader)
 * @param modifier 外层容器 Modifier (默认 wrap; 全屏场景传 fillMaxSize)
 * @param imageModifier 图片 Modifier (默认 fillMaxSize; 对话框场景传定高约束)
 * @param book 当前书籍 (http 场景判断 isLocal), 可空
 * @param bookSource 书源 (网络图防盗链 header/cookie/charset/JS), 可空
 * @param onLongPress 长按回调 (app 端长按保存等场景), 默认无
 * @param loadingContent 加载中/失败占位 (默认 i18n "loading" 文案, 对照原 DesktopPhotoDialog)
 */
@Composable
fun PhotoDialogContent(
    src: String,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier.fillMaxSize(),
    book: Book? = null,
    bookSource: BookSource? = null,
    onLongPress: (() -> Unit)? = null,
    loadingContent: @Composable () -> Unit = { Text(rememberString("loading")) },
) {
    val bitmap by produceState<ImageBitmap?>(null, src) {
        value = runCatching {
            ImageBitmapLoader().loadBitmap(src, book, bookSource)
        }.getOrNull()
    }
    // GIF 动图旁路: 大图查看是唯一值得逐帧播放的场景, 故额外取一次裸字节解码。
    // Desktop 使用 Skiko Codec；Android/iOS/鸿蒙交给平台图片管线，无法逐帧时退化静态图。
    // 非 GIF 字节不进解码器, 静态图仅多一次带缓存的字节读取。
    val gifBytes by produceState<ByteArray?>(null, src) {
        value = runCatching {
            ImageBitmapLoader().loadBytes(src, book, bookSource)
        }.getOrNull()?.takeIf { isGifBytes(it) }
    }
    val animatedFrame = rememberAnimatedImageBitmap(gifBytes)
    Box(modifier, contentAlignment = Alignment.Center) {
        (animatedFrame ?: bitmap)?.let { b ->
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                // clipToBounds 在 zoomable 的 graphicsLayer 之前: 放大后不溢出图片布局边界
                modifier = imageModifier
                    .clipToBounds()
                    .zoomable(
                        contentAspectRatio = b.width.toFloat() / b.height,
                        onLongPress = onLongPress,
                    ),
            )
        } ?: loadingContent()
    }
}

/**
 * 大图查看对话框 (AppAlertDialog 形态, 视觉对照原 desktop DesktopPhotoDialog:
 * 内容区 + "关闭"按钮, 图片区占对话框高 0.8)。desktop/iOS/鸿蒙三端共用;
 * app 端走全屏 DialogFragment 版 [io.legado.app.ui.widget.dialog.PhotoDialog], 不经本件。
 *
 * @param src 图片路径
 * @param onDismiss 关闭回调
 * @param book 当前书籍, 可空 (透传 [PhotoDialogContent])
 * @param bookSource 书源 (网络图防盗链), 可空 (透传 [PhotoDialogContent])
 */
@Composable
fun PhotoViewDialog(
    src: String,
    onDismiss: () -> Unit,
    book: Book? = null,
    bookSource: BookSource? = null,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        okButton = AlertButton(rememberString("close")),
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            PhotoDialogContent(
                src = src,
                imageModifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
                book = book,
                bookSource = bookSource,
            )
        }
    }
}
