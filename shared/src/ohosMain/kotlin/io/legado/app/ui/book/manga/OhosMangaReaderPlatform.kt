package io.legado.app.ui.book.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.image.MangaImageBytesLoader
import io.legado.app.help.image.ohosDecodeImageBytes
import io.legado.app.napi.OhosDownloadProgressEvents
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.ui.book.manga.render.MangaSkiaImage
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.imageExtension
import io.legado.app.utils.File
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// 鸿蒙漫画阅读平台能力: 图片流复用 shared 提取器, 渲染用 Compose Image
// (鸿蒙无 coil3 变体, 取字节直接调 MangaImageBytesLoader, 再经 CPF 门面解码)
object OhosMangaReaderPlatform : MangaReaderScreenModel.Platform {

    // 异步取字节 + CPF 门面解码 + Compose Image 渲染
    @Composable
    override fun Image(
        url: String,
        modifier: Modifier,
        horizontal: Boolean,
        book: Book?,
        source: BookSource?,
        colorFilterConfig: MangaColorFilterConfig,
        grayEnabled: Boolean,
        onLoadState: (MangaCellState) -> Unit,
        retryTick: Int,
        onProgress: (String) -> Unit,
    ) {
        val colorFilter = remember(colorFilterConfig, grayEnabled) {
            mangaColorFilter(colorFilterConfig, grayEnabled)
        }
        val currentOnProgress by rememberUpdatedState(onProgress)
        DisposableEffect(url) {
            OhosDownloadProgressEvents.addListener(url) { _, percentage, bytesRead, totalBytes ->
                currentOnProgress(
                    if (percentage > 0) "$percentage%" else {
                        val kb = bytesRead / 1024.0
                        if (kb >= 1024) {
                            val mb = (kb / 1024.0 * 10).roundToInt() / 10.0
                            "${mb}MB"
                        } else "${kb.toInt()}KB"
                    }
                )
            }
            onDispose { OhosDownloadProgressEvents.removeListener(url) }
        }
        MangaSkiaImage(
            url = url,
            modifier = modifier,
            horizontal = horizontal,
            book = book,
            source = source,
            colorFilter = colorFilter,
            onLoadState = onLoadState,
            retryTick = retryTick,
        )
    }

    /** 保存图片：先取得原始字节，再按魔数生成正确扩展名后写入导出目录。 */
    override suspend fun saveImage(
        url: String,
        book: Book?,
        source: BookSource?,
    ): Boolean = withContext(IoDispatcher) {
        book ?: return@withContext false
        runCatching {
            val bytes = MangaImageBytesLoader.load(url, book, source, currentCoroutineContext())
                ?: return@runCatching false
            val name = "manga-${systemCurrentTimeMillis()}${imageExtension(bytes, url)}"
            val destPath = PlatformServiceProviders.get().files.saveFile(name)
                ?: return@runCatching false
            File(destPath).writeBytes(bytes)
            true
        }.getOrElse {
            AppLog.put("保存图片出错\n${it.message}", it)
            false
        }
    }

    // 无 Coil3 内存缓存: 预载 = 提前经共享 MangaImageBytesLoader 取字节回填磁盘缓存
    // (BookImageStorage, 与显示端同链路), 翻到预载区间时命中缓存跳过网络下载;
    // 解码留给显示端 (位图无跨页共享缓存层)
    override suspend fun preloadImage(url: String, book: Book, source: BookSource?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        runCatching {
            MangaImageBytesLoader.load(url, book, source, currentCoroutineContext())
        }
    }
}

/**
 * 漫画页取图: 网络图走共享 [MangaImageBytesLoader] 完整链路 (图片缓存 → 本地书 FileBook →
 * AnalyzeUrl 防盗链 header 下载 → ImageUtils.decode 解密 → 回写缓存), 与 app/desktop/iOS 同源;
 * cbz:// 与本地路径仍走 [ImageBitmapLoader] (其 cbz 分支经 ArchiveProviders 抽条目字节)。
 */
private suspend fun loadMangaBitmap(
    url: String,
    book: Book?,
    source: BookSource?,
): ImageBitmap? {
    if (book != null && (url.startsWith("http://") || url.startsWith("https://"))) {
        return withContext(IoDispatcher) {
            runCatching {
                MangaImageBytesLoader.load(url, book, source, currentCoroutineContext())
            }.getOrNull()?.let { ohosDecodeImageBytes(it) }
        }
    }
    return ImageBitmapLoader().loadBitmap(url, book, source)
}
