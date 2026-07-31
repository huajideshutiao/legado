package io.legado.desktop.ui.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.ui.book.manga.MangaImageExtractorShared
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.isNoOp
import io.legado.app.ui.book.manga.config.toColorMatrix
import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.GSON
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import io.legado.desktop.ui.component.rememberCoverPainter
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
 * - Image: Coil3 [rememberCoverPainter] (走共享 SingletonImageLoader, 含防盗链 header/缓存),
 *   colorFilter 用 Compose [ColorFilter.colorMatrix] (与 app 端 ColorMatrixColorFilter 同矩阵),
 *   grayEnabled 用灰度 ColorMatrix (对照 app 端 Coil3 灰度变换)
 * - toggle/update*: 写回 [PreferenceProviders] 同 key (与 app 端 AppConfig = value 等价)
 * - getBatteryLevel: 桌面端无电池概念, 返回 -1 (信息条不显示电量)
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
            autoPageSpeed = prefs.getInt(PreferKey.mangaAutoPageSpeed, 0),
            grayEnabled = prefs.getBoolean(PreferKey.enableMangaGray, false),
            colorFilterConfig = runCatching {
                GSON.fromJsonObject<MangaColorFilterConfig>(
                    prefs.getString(PreferKey.mangaColorFilter, "")
                ).getOrNull()
            }.getOrNull() ?: MangaColorFilterConfig(),
            gifAutoNext = prefs.getBoolean(PreferKey.enableMangaGifAutoNext, false),
        )

    // 桌面端无电池概念
    override fun getBatteryLevel(): Int = -1

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
    ) {
        // 走共享 SingletonImageLoader (防盗链 header/缓存/解密), sourceOrigin 注入书源 header
        val painter = rememberCoverPainter(url, source?.bookSourceUrl)
        val state by painter.state.collectAsState()
        // 合并颜色滤镜: colorFilterConfig 矩阵 + 灰度矩阵 (对照 app 端 view.colorFilter + loadPageImage gray)
        val colorFilter = remember(colorFilterConfig, grayEnabled) {
            buildColorFilter(colorFilterConfig, grayEnabled)
        }
        Box(modifier.background(Color.Black)) {
            if (state is AsyncImagePainter.State.Success) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    // 横向滚动: FIT_CENTER (等比留白); 纵向滚动: FIT_XY (填满, 对照 app 端 ScaleType.FIT_XY)
                    contentScale = if (horizontal) ContentScale.Fit else ContentScale.FillBounds,
                    colorFilter = colorFilter,
                )
            }
        }
    }

    /**
     * 合并颜色滤镜 (colorFilterConfig + grayEnabled) 为单个 [ColorFilter]。
     *
     * - 仅 grayEnabled: 灰度矩阵 (对照 app 端 Coil3 GrayscaleTransformation)
     * - 仅 colorFilterConfig: 配置矩阵 (对照 app 端 ColorMatrixColorFilter)
     * - 两者均启用: 配置矩阵 × 灰度矩阵。app 端灰度在解码期变换像素, 调色在绘制期作用于
     *   已灰度的图, 故顺序是先灰度后调色
     * - 均不启用: null
     */
    private fun buildColorFilter(
        config: MangaColorFilterConfig,
        grayEnabled: Boolean
    ): ColorFilter? {
        val cfgNoOp = config.isNoOp()
        if (cfgNoOp && !grayEnabled) return null
        if (cfgNoOp && grayEnabled) return ColorFilter.colorMatrix(ColorMatrix(GRAYSCALE_MATRIX))
        if (!grayEnabled) return ColorFilter.colorMatrix(ColorMatrix(config.toColorMatrix()))
        // 两者均启用: 先灰度后调色 = 配置矩阵 × 灰度矩阵
        return ColorFilter.colorMatrix(
            ColorMatrix(
                mergeMatrices(
                    config.toColorMatrix(),
                    GRAYSCALE_MATRIX
                )
            )
        )
    }

    /** 4x5 矩阵合成 (result = a × b, 即先 b 后 a), 第 5 列为平移项 */
    private fun mergeMatrices(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0..3) {
            for (col in 0..3) {
                var sum = 0f
                for (k in 0..3) {
                    sum += a[row * 5 + k] * b[k * 5 + col]
                }
                out[row * 5 + col] = sum
            }
            // 平移列: a 的线性部分作用于 b 的平移量, 再叠加 a 自身平移
            var offset = a[row * 5 + 4]
            for (k in 0..3) {
                offset += a[row * 5 + k] * b[k * 5 + 4]
            }
            out[row * 5 + 4] = offset
        }
        return out
    }

    /**
     * 保存图片到 [destPath] (destPath 由 shared 路由经 FileDialogs 保存框取得的绝对路径)。
     *
     * 对照 app 端 AndroidMangaReaderPlatform: 优先本地图片缓存, 本地书走 FileBook;
     * 桌面端漫画走 Coil3 直连不落 BookImageStorage 缓存, 故再补一层按书源下载
     * (AnalyzeUrl + ImageUtils.decode, 同 app 端 BookHelp.saveImage 的取字节链路)。
     * 落盘后按魔数校正扩展名 (对照 app 端 FileUtils.saveImage 的 getImageExtension)。
     */
    override suspend fun saveImage(
        url: String,
        book: Book?,
        source: BookSource?,
        destPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        book ?: return@withContext false
        runCatching {
            val bytes = readCachedImage(book, url)
                ?: readLocalBookImage(book, url)
                ?: downloadImage(url, book, source)
                ?: return@runCatching false
            val dest = File(destPath)
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            fixExtension(dest)
            true
        }.getOrElse {
            AppLog.put("保存图片出错\n${it.localizedMessage}", it)
            false
        }
    }

    /** 读本地图片缓存; JvmBookImageStorage 路径仅由 book+url 派生, chapter 只参与签名, 占位即可。 */
    private fun readCachedImage(book: Book, url: String): ByteArray? = runCatching {
        val chapter = BookChapter(url = url, bookUrl = book.bookUrl)
        val path = BookImageStorageProviders.get().getImagePath(book, chapter, url) ?: return null
        File(path).takeIf { it.isFile }?.readBytes()
    }.getOrNull()

    /** 本地书 (epub/cbz 等) 内嵌图片, 对照 app 端 BaseReadViewModel.saveImage 的 FileBook 分支。 */
    private fun readLocalBookImage(book: Book, url: String): ByteArray? {
        if (!book.isLocal) return null
        return runCatching { FileBook.getImage(book, url)?.use { it.readBytes() } }.getOrNull()
    }

    /** 按书源下载并解密, 对照 app 端 BookHelp.saveImage 的 getByteArrayAwait + ImageUtils.decode。 */
    private suspend fun downloadImage(url: String, book: Book, source: BookSource?): ByteArray? {
        val bytes = AnalyzeUrlFactories.create(
            rawUrl = url,
            source = source,
            coroutineContext = currentCoroutineContext()
        ).getByteArrayAwait()
        return runScriptWithContext { ImageUtils.decode(url, bytes, false, source, book) }
    }

    /** 路由建议名恒为 `.jpg`, 与实际格式不符时按魔数改名 (与 app 端一致)。 */
    private fun fixExtension(dest: File) {
        val ext = FileUtilsBase.getImageExtension(dest)
        if (dest.name.endsWith(ext, ignoreCase = true)) return
        dest.renameTo(File(dest.parentFile, dest.nameWithoutExtension + ext))
    }

    override fun updateColorFilter(config: MangaColorFilterConfig) {
        prefs.putString(PreferKey.mangaColorFilter, GSON.toJson(config))
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

    // 标准 ITU-R BT.601 灰度矩阵 (对照 Coil3 GrayscaleTransformation)
    private val GRAYSCALE_MATRIX = floatArrayOf(
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}
