package io.legado.app.ui.book.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

// 鸿蒙漫画阅读平台能力: 图片流复用 shared 提取器, 渲染用 ImageBitmapLoader + Compose Image
// (ImageBitmapLoader 经 Skia 解码, 同 iOS/desktop 端 makeFromEncoded 路径)
object OhosMangaReaderPlatform : MangaReaderScreenModel.Platform {

    // 图片 URL 提取: 复用 commonMain 的 MangaImageExtractorShared (与 iOS/desktop 同源)
    override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> =
        MangaImageExtractorShared.extractImageUrls(content).asFlow()

    // ImageBitmapLoader 异步加载 + Compose Image 渲染 (经 Skia 解码, 同 desktop 端)
    @Composable
    override fun Image(
        url: String,
        modifier: Modifier,
        horizontal: Boolean,
        book: Book?,
        source: BookSource?,
        colorFilterConfig: MangaColorFilterConfig,
        grayEnabled: Boolean,
    ) {
        var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
        var failed by remember(url) { mutableStateOf(false) }

        LaunchedEffect(url) {
            bitmap = ImageBitmapLoader().loadBitmap(url, book, source)
            if (bitmap == null) failed = true
        }

        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = modifier,
                contentScale = if (horizontal) ContentScale.Fit else ContentScale.FillWidth,
            )
        } else if (failed) {
            // 加载失败占位 (同 iOS UIImageView image 为 nil 时的空白)
            Box(
                modifier = modifier.background(Color.DarkGray),
                contentAlignment = Alignment.Center,
            ) {
                Text("图片加载失败", color = Color.White)
            }
        } else {
            // 加载中占位
            Box(modifier = modifier.background(Color.Black))
        }
    }
}
