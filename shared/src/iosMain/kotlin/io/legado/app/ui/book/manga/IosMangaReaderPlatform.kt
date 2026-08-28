package io.legado.app.ui.book.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.DownloadProgressRegistry
import io.legado.app.help.image.MangaImageBytesLoader
import io.legado.app.model.manga.MangaModel
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

// iOS 漫画阅读平台能力: 图片流复用 shared 提取器, 渲染走 Coil3 (对照 DesktopMangaReaderPlatform)
object IosMangaReaderPlatform : MangaReaderScreenModel.Platform {

    private val prefs get() = PreferenceProviders.get()

    // 启动即读持久化配置 (对照原版 ReadMangaActivity 直读 AppConfig; 与 desktop 同 key 同默认值,
    // 菜单写入经 toggle/update* 走 PreferenceProviders 持久化)
    override val config: MangaReaderConfig
        get() = MangaReaderConfig(
            hideMangaTitle = prefs.getBoolean(PreferKey.hideMangaTitle, false),
            preDownloadNum = prefs.getInt(PreferKey.mangaPreDownloadNum, 10),
            syncBookProgressPlus = prefs.getBoolean(PreferKey.syncBookProgressPlus, false),
            horizontal = prefs.getBoolean(PreferKey.enableMangaHorizontalScroll, false),
            // 默认 3: 对齐 app 端 AppConfig.mangaAutoPageSpeed (0 会让定时翻页退化成空转)
            autoPageSpeed = prefs.getInt(PreferKey.mangaAutoPageSpeed, 3),
            grayEnabled = prefs.getBoolean(PreferKey.enableMangaGray, false),
            colorFilterConfig = runCatching {
                GSON.fromJsonObject<MangaColorFilterConfig>(
                    prefs.getString(PreferKey.mangaColorFilter, "")
                ).getOrNull()
            }.getOrNull() ?: MangaColorFilterConfig(),
            gifAutoNext = prefs.getBoolean(PreferKey.enableMangaGifAutoNext, false),
            disablePageAnim = prefs.getBoolean(PreferKey.disableMangaPageAnim, false),
            footerConfig = runCatching {
                GSON.fromJsonObject<MangaFooterConfig>(
                    prefs.getString(PreferKey.mangaFooterConfig, "")
                ).getOrNull()
            }.getOrNull() ?: MangaFooterConfig(),
        )

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
        onLoadState: (MangaCellState) -> Unit,
        retryTick: Int,
        onProgress: (String) -> Unit,
    ) {
        val colorFilter = remember(colorFilterConfig, grayEnabled) {
            mangaColorFilter(colorFilterConfig, grayEnabled)
        }
        val currentOnProgress by rememberUpdatedState(onProgress)
        DisposableEffect(url) {
            val remove = DownloadProgressRegistry.addListener(url) { bytes, total ->
                currentOnProgress(
                    if (total != null && total > 0) "${bytes * 100 / total}%" else {
                        val kb = bytes / 1024.0
                        if (kb >= 1024) {
                            val mb = (kb / 1024.0 * 10).roundToInt() / 10.0
                            "${mb}MB"
                        } else "${kb.toInt()}KB"
                    }
                )
            }
            onDispose { remove() }
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

    // 保存图片：先取得原始字节，再按魔数生成正确扩展名后写入导出目录。
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

    // 预载到内存缓存: WRITE_ONLY 只写不返回图 (与 desktop 同参; 显示端 rememberAsyncImagePainter
    // 与预载经 SingletonImageLoader 共用同一实例, memoryCacheKey(url) 同 key, 翻到预载区间即秒显)
    override suspend fun preloadImage(url: String, book: Book, source: BookSource?) {
        runCatching {
            val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(MangaModel(url, book, source))
                .memoryCacheKey(url)
                .memoryCachePolicy(CachePolicy.WRITE_ONLY)
                .diskCachePolicy(CachePolicy.DISABLED)
                .size(Size.ORIGINAL)
                .build()
            SingletonImageLoader.get(PlatformContext.INSTANCE).execute(request)
        }
    }
}
