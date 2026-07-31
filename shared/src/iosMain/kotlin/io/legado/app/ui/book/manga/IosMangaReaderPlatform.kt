package io.legado.app.ui.book.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.manga.MangaModel
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

// iOS 漫画阅读平台能力: 图片流复用 shared 提取器, 渲染走 Coil3 (对照 DesktopMangaReaderPlatform)
object IosMangaReaderPlatform : MangaReaderScreenModel.Platform {

    // 图片 URL 提取: 复用 commonMain 的 MangaImageExtractorShared (与 desktop 同源)
    override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> =
        MangaImageExtractorShared.extractImageUrls(content).asFlow()

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
        // 重试计数进 remember key: 变化即重建 ImageRequest, 重新走一次 fetch (对照 app 端 retry())
        var retryTick by remember(url) { mutableStateOf(0) }
        val request = remember(url, book, source, retryTick) {
            ImageRequest.Builder(PlatformContext.INSTANCE)
                // MangaModel 走 MangaModelFetcher: 图片缓存 + AnalyzeUrl(防盗链 header) + 解密,
                // 裸 url 直连对需要处理的源必然全失败
                .data(book?.let { MangaModel(url, it, source) })
                // 磁盘缓存由 MangaImageBytesLoader 自管, 内存缓存保留
                .memoryCacheKey(url)
                .diskCachePolicy(CachePolicy.DISABLED)
                .size(Size.ORIGINAL)
                .build()
        }
        val painter = rememberAsyncImagePainter(request)
        val state by painter.state.collectAsState()
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            when (state) {
                is AsyncImagePainter.State.Success -> Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    // 横向滚动: 等比留白; 纵向滚动: 填满 (对照 app 端 ScaleType.FIT_XY)
                    contentScale = if (horizontal) ContentScale.Fit else ContentScale.FillBounds,
                )

                is AsyncImagePainter.State.Error -> Text(
                    text = "重新加载",
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { retryTick++ }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                else -> CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}
