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
 * 与 app 端 [BookInfoBlurCoverBg] 区别: 不使用 Android 专属 BookInfoBgTransformation (渐变遮罩),
 * 改用 Modifier.blur + 纯色叠层, 视觉接近但简化, 适合非 Android 平台。
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
        // 模糊封面铺满
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(24.dp),
            )
        }
        // accent 半透明遮罩 (加载中/失败时即纯色占位)
        Box(Modifier.fillMaxSize().background(AppTheme.colors.accent.copy(alpha = 0.15f)))
        // 加深底色保证标题栏文字可读
        Box(
            Modifier.fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.2f))
        )
    }
}
