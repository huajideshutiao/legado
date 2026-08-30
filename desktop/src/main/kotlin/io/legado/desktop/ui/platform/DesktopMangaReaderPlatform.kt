package io.legado.desktop.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.help.image.MangaImageBytesLoader
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.ui.book.manga.mangaColorFilter
import io.legado.app.ui.book.manga.render.MangaSkiaImage
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.imageExtension
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.desktop.help.DesktopBattery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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

    // Windows 经 kernel32 GetSystemPowerStatus 读真实电量; 无电池/失败回落 100 (信息条恒显示)
    override fun getBatteryLevel(): Int = DesktopBattery.getBatteryLevel()

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
    ): Boolean? = withContext(Dispatchers.IO) {
        book ?: return@withContext false
        runCatching {
            val bytes = MangaImageBytesLoader.load(url, book, source, currentCoroutineContext())
                ?: return@runCatching false
            val name = "manga-${systemCurrentTimeMillis()}${imageExtension(bytes, url)}"
            val destPath = PlatformServiceProviders.get().files.saveFile(name)
                ?: return@runCatching null
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

    /** 预载到磁盘缓存 (经 MangaImageBytesLoader 下载解密并写入 BookImageStorage, 与显示端同链路) */
    override suspend fun preloadImage(url: String, book: Book, source: BookSource?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        runCatching {
            MangaImageBytesLoader.load(url, book, source, currentCoroutineContext())
        }
    }

}
