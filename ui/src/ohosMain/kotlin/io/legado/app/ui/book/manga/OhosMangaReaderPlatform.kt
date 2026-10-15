package io.legado.app.ui.book.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.napi.OhosDownloadProgressEvents
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.ui.book.manga.render.MangaSkiaImage
import io.legado.app.ui.book.manga.render.mangaProgressText
import io.legado.app.ui.book.manga.render.preloadMangaImage
import io.legado.app.ui.root.PlatformServiceProviders
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

    /**
     * 保存图片: 只导出已缓存的图 (对照原版 BaseReadViewModel.saveImage 的读取段) → 按实际格式命名
     * → 写进本端"图片保存"落点。
     *
     * 与图像查看器长按保存同一入口 ([io.legado.app.ui.root.FilePickerService.saveImageToUser]:
     * 本端为系统相册), 目录记忆与选定目录的持久化授权由该入口统一处理。
     */
    override suspend fun saveImage(
        url: String,
        book: Book?,
        source: BookSource?,
    ): Boolean? = withContext(IoDispatcher) {
        saveCachedMangaImage(url, book) { name, bytes ->
            PlatformServiceProviders.get().files.saveImageToUser(name, bytes)
        }
    }

    // 预载: 下载解密写入磁盘缓存 + 顺带解码进 DecodedBitmapCache (与显示端同链路同 key)
    override suspend fun preloadImage(url: String, book: Book, source: BookSource?) {
        preloadMangaImage(url, book, source)
    }
}
