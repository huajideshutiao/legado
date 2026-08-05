package io.legado.app.ui.book.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 模糊封面背景 Coil3 实现 (desktop/iOS 共享)。
 *
 * 走 [BookImageLoaders] (各端注入 Coil3 实现) 加载封面 Bitmap, 再用 [Modifier.blur] 做高斯模糊。
 * 加载中/失败/E-Ink 模式回退到 accent 半透明纯色占位 (与 [SharedBlurCoverBgPlaceholder] 一致)。
 *
 * 渐变蒙版对照 app 端 [io.legado.app.ui.book.info.BookInfoBgTransformation]:
 * 顶部 30% 清晰 → 30%-65% 柔和过渡 → 底部透明 (DST_IN alpha 蒙版), 再叠压暗蒙层
 * (原版 argb(50,0,0,0) SRC_ATOP ≈ 压暗 20%)。用 Compose 绘制层实现, 免 Android 位图 API。
 *
 * @param book 当前书籍 (可能为 null)
 * @param coverTick 封面重载 key (变更时重新加载)
 * @param inBookshelf 是否在书架 (本简化实现未使用, 保留签名对齐 slot 契约)
 * @param isEInkMode E-Ink 模式跳过图片加载 (直接走纯色占位)
 * @param modifier 调用方传入的尺寸约束
 */
@Composable
fun SharedBlurCoverBgCoil(
    book: Book?,
    coverTick: Int,
    inBookshelf: Boolean,
    isEInkMode: Boolean,
    modifier: Modifier,
    land: Boolean = false,
) {
    val cover = book?.getDisplayCover()
    val loader = remember { BookImageLoaders.getOrNull() }
    var bitmap by remember(cover, coverTick) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cover, coverTick, loader, isEInkMode) {
        if (isEInkMode || cover.isNullOrBlank() || loader == null) return@LaunchedEffect
        loader.loadImage(
            url = cover,
            sourceOrigin = book?.origin,
            onSuccess = { bitmap = it },
            onError = { bitmap = null },
        )
    }
    Box(modifier) {
        // 模糊封面铺满 + 渐变蒙版 + 压暗 (对照原版 BookInfoBgTransformation)
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // 链序关键: drawWithContent 在 blur 外层, 否则渐变蒙版/压暗也会被高斯模糊
                modifier = Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    // 渐变蒙版 (顶部 30% 清晰 → 底部透明, 曲线同原版) 仅竖屏顶部条使用;
                    // 横屏左半列整列铺满时任何方向的"一边黑"渐变都不合适 (用户反馈),
                    // 横屏只保留均匀压暗, 整列均匀模糊
                    if (!land) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.00f to Color.Black,
                                    0.30f to Color.Black,
                                    0.48f to Color(0xD2000000),
                                    0.63f to Color(0xA5000000),
                                    0.77f to Color(0x6E000000),
                                    0.90f to Color(0x1E000000),
                                    1.00f to Color.Transparent,
                                ),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    // 压暗蒙层 (对照原版 argb(50,0,0,0) SRC_ATOP)
                    drawRect(color = Color(0x32000000))
                }.blur(24.dp),
            )
        }
        // 加载中/失败/E-Ink 占位: 原纯色占位视觉 (对照 SharedBlurCoverBgPlaceholder)
        if (bitmap == null) {
            Box(Modifier.fillMaxSize().background(AppTheme.colors.accent.copy(alpha = 0.15f)))
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
            )
        }
    }
}
