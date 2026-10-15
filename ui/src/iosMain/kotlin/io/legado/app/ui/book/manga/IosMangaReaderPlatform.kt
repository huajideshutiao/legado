package io.legado.app.ui.book.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.DownloadProgressRegistry
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.ui.book.manga.render.MangaSkiaImage
import io.legado.app.ui.book.manga.render.mangaProgressText
import io.legado.app.ui.book.manga.render.preloadMangaImage
import io.legado.app.ui.book.read.IosReaderPlatformProvider
import io.legado.app.ui.root.PlatformServiceProviders
import kotlinx.coroutines.withContext

// iOS 漫画阅读平台能力: 图片流复用 shared 提取器, 渲染走 Skia 三端共用的 MangaSkiaImage
// (本端只负责把 DownloadProgressRegistry 的下载进度接到转圈环心)
object IosMangaReaderPlatform : MangaReaderScreenModel.Platform {

    // 信息条电量: 不覆写时走接口默认值 100, 与 Android/桌面(读真实电量)以及本端小说阅读页
    // (UIDevice 真实电量) 都不一致。这里直接复用小说阅读页那一份实现, 保证两页口径唯一。
    override fun getBatteryLevel(): Int = IosReaderPlatformProvider.getBatteryLevel()

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
            val remove = DownloadProgressRegistry.addListener(url) { bytes, total ->
                currentOnProgress(mangaProgressText(bytes, total ?: 0L))
            }
            onDispose { remove() }
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

    // 保存图片：只导出已缓存的图 (对照原版 BaseReadViewModel.saveImage 的读取段), 落点走"图片保存"
    // (对照原版 ReadMangaActivity.saveImage 与图像查看器同一目录记忆口径)
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
