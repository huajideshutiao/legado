package io.legado.app.ui.book.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.image.MangaImageBytesLoader
import io.legado.app.napi.OhosDownloadProgressEvents
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.ui.book.manga.render.MangaSkiaImage
import io.legado.app.ui.book.manga.render.mangaProgressText
import io.legado.app.ui.book.manga.render.preloadMangaImage
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.imageExtension
import io.legado.app.utils.File
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

// 鸿蒙漫画阅读平台能力: 图片流复用 shared 提取器, 渲染走 Skia 三端共用的 MangaSkiaImage
// (本端只负责把 OhosDownloadProgressEvents 的下载进度接到转圈环心)
object OhosMangaReaderPlatform : MangaReaderScreenModel.Platform {

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
        val currentOnProgress by rememberUpdatedState(onProgress)
        DisposableEffect(url) {
            OhosDownloadProgressEvents.addListener(url) { _, _, bytesRead, totalBytes ->
                currentOnProgress(mangaProgressText(bytesRead, totalBytes))
            }
            onDispose { OhosDownloadProgressEvents.removeListener(url) }
        }
        MangaSkiaImage(
            url = url,
            modifier = modifier,
            horizontal = horizontal,
            book = book,
            source = source,
            colorFilterConfig = colorFilterConfig,
            grayEnabled = grayEnabled,
            onLoadState = onLoadState,
            retryTick = retryTick,
        )
    }

    /** 保存图片：先取得原始字节，再按魔数生成正确扩展名后写入导出目录。 */
    override suspend fun saveImage(
        url: String,
        book: Book?,
        source: BookSource?,
    ): Boolean? = withContext(IoDispatcher) {
        book ?: return@withContext false
        runCatching {
            val bytes = MangaImageBytesLoader.load(url, book, source, currentCoroutineContext())
                ?: return@runCatching false
            val name = "manga-${systemCurrentTimeMillis()}${imageExtension(bytes, url)}"
            val destPath = PlatformServiceProviders.get().files.saveFile(name)
                ?: return@runCatching null
            File(destPath).writeBytes(bytes)
            true
        }.getOrElse {
            AppLog.put("保存图片出错\n${it.message}", it)
            false
        }
    }

    // 预载: 下载解密写入磁盘缓存 + 顺带解码进 DecodedBitmapCache (与显示端同链路同 key)
    override suspend fun preloadImage(url: String, book: Book, source: BookSource?) {
        preloadMangaImage(url, book, source)
    }
}
