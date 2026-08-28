package io.legado.desktop.ui.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
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
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.help.image.MangaImageBytesLoader
import io.legado.app.model.manga.MangaModel
import io.legado.app.ui.book.manga.MangaImageExtractorShared
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.manga.mangaColorFilter
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.isNoOp
import io.legado.app.ui.book.manga.config.toColorMatrix
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.ui.book.manga.render.MangaSkiaImage
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.imageExtension
import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.toJson
import io.legado.desktop.help.DesktopBattery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * desktop 端 [MangaReaderScreenModel.Platform] 实现 (Coil3 图片渲染)。
 *
 * 对照 app 端 [io.legado.app.ui.book.manga.AndroidMangaReaderPlatform]:
 * - config: 直读 [PreferenceProviders] 同 key PreferKey (与 app 端 AppConfig 同源),
 *   未纳入 [io.legado.app.help.config.AppConfigAccessor] 接口
 * - flowImages: 复用 shared [MangaImageExtractorShared] (与 app 端 BookHelp.flowImages 同一提取逻辑)
 * - Image: Coil3 [MangaModel] 请求 (走 shared MangaModelFetcher: 图片缓存 + AnalyzeUrl 防盗链
 *   header + 解密, 与 app 端 MangaPageImageView 同一条链路),
 *   colorFilter 用 Compose [ColorFilter.colorMatrix] (与 app 端 ColorMatrixColorFilter 同矩阵),
 *   grayEnabled 用灰度 ColorMatrix (对照 app 端 Coil3 灰度变换)
 * - toggle/update*: 写回 [PreferenceProviders] 同 key (与 app 端 AppConfig = value 等价)
 * - getBatteryLevel: Windows 经 kernel32 (JNA) / macOS 经 `pmset -g batt` /
 *   Linux 经 sysfs BAT/capacity 读真实电量, 无电池/失败回落 100 (信息条恒显示电量)
 * - saveImage: 本地缓存 → 本地书 FileBook → 按书源下载, 写入 destPath
 */
object DesktopMangaReaderPlatform : MangaReaderScreenModel.Platform {

    private val prefs get() = PreferenceProviders.get()

    override val config: io.legado.app.ui.book.manga.MangaReaderConfig
        get() = io.legado.app.ui.book.manga.MangaReaderConfig(
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

    // Windows 经 kernel32 GetSystemPowerStatus 读真实电量; 无电池/失败回落 100 (信息条恒显示)
    override fun getBatteryLevel(): Int = DesktopBattery.getBatteryLevel()

    override fun toggleHorizontal(): Boolean {
        val enable = !prefs.getBoolean(PreferKey.enableMangaHorizontalScroll, false)
        prefs.putBoolean(PreferKey.enableMangaHorizontalScroll, enable)
        return enable
    }

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
        DisposableEffect(url) {
            ProgressManager.addListener(url) { _, percentage, bytesRead, totalBytes ->
                onProgress(
                    if (totalBytes > 0) {
                        "$percentage%"
                    } else {
                        val kb = bytesRead / 1024.0
                        if (kb >= 1024) String.format("%.1fMB", kb / 1024)
                        else "${kb.toInt()}KB"
                    }
                )
            }
            onDispose { ProgressManager.removeListener(url) }
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

    /**
     * 保存图片到 [destPath] (destPath 由 shared 路由经 FileDialogs 保存框取得的绝对路径)。
     *
     * 取字节直接复用 [MangaImageBytesLoader] (图片缓存 → 本地书 FileBook → 按书源下载+解密),
     * 与阅读页同一条链路。落盘后按魔数校正扩展名 (对照 app 端 FileUtils.saveImage)。
     */
    override suspend fun saveImage(
        url: String,
        book: Book?,
        source: BookSource?,
    ): Boolean = withContext(Dispatchers.IO) {
        book ?: return@withContext false
        runCatching {
            val bytes = MangaImageBytesLoader.load(url, book, source, currentCoroutineContext())
                ?: return@runCatching false
            val name = "manga-${systemCurrentTimeMillis()}${imageExtension(bytes, url)}"
            val destPath = PlatformServiceProviders.get().files.saveFile(name)
                ?: return@runCatching false
            File(destPath).apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
            true
        }.getOrElse {
            AppLog.put("保存图片出错\n${it.localizedMessage}", it)
            false
        }
    }

    override fun updateColorFilter(config: MangaColorFilterConfig) {
        // 走模型自带 toJson (全零返回 "", 与原版 MangaColorFilterDialog 一致);
        // 注意不能用 GSON.toJson 直接序列化非 @Serializable 旧实现, 会落成 toString 垃圾串无法回读
        prefs.putString(PreferKey.mangaColorFilter, config.toJson())
    }

    override fun updateGray(enable: Boolean) {
        prefs.putBoolean(PreferKey.enableMangaGray, enable)
    }

    override fun updateFooterConfig(config: MangaFooterConfig) {
        prefs.putString(PreferKey.mangaFooterConfig, GSON.toJson(config))
    }

    override fun toggleHideTitle(): Boolean {
        val enable = !prefs.getBoolean(PreferKey.hideMangaTitle, false)
        prefs.putBoolean(PreferKey.hideMangaTitle, enable)
        return enable
    }

    override fun toggleDisablePageAnim(): Boolean {
        val enable = !prefs.getBoolean(PreferKey.disableMangaPageAnim, false)
        prefs.putBoolean(PreferKey.disableMangaPageAnim, enable)
        return enable
    }

    override fun toggleGifAutoNext(): Boolean {
        val enable = !prefs.getBoolean(PreferKey.enableMangaGifAutoNext, false)
        prefs.putBoolean(PreferKey.enableMangaGifAutoNext, enable)
        return enable
    }

    override fun setPreDownloadNum(num: Int) {
        prefs.putInt(PreferKey.mangaPreDownloadNum, num)
    }

    override fun setAutoPageSpeed(speed: Int) {
        prefs.putInt(PreferKey.mangaAutoPageSpeed, speed)
    }

    /** 预载到内存缓存: WRITE_ONLY 只写不返回图 (对照 app 端 installCoilPreloader) */
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
