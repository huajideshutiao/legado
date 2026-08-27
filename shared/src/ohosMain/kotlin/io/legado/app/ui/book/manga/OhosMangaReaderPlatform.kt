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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import io.legado.app.utils.File
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// 鸿蒙漫画阅读平台能力: 图片流复用 shared 提取器, 渲染用 Compose Image
// (鸿蒙无 coil3 变体, 取字节直接调 MangaImageBytesLoader, 再经 CPF 门面解码)
object OhosMangaReaderPlatform : MangaReaderScreenModel.Platform {

    private val prefs get() = PreferenceProviders.get()

    // 启动即读持久化配置 (对照原版 ReadMangaActivity 直读 AppConfig; 与 desktop/iOS 同 key 同默认值)
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

    // 图片 URL 提取: 复用 commonMain 的 MangaImageExtractorShared (与 iOS/desktop 同源)
    override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> =
        MangaImageExtractorShared.extractImageUrls(content).asFlow()

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
        var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
        var failed by remember(url) { mutableStateOf(false) }

        // retryTick 变化即重新取字节 (shared 单元格"重新加载"点击驱动)
        LaunchedEffect(url, retryTick) {
            bitmap = loadMangaBitmap(url, book, source)
            if (bitmap == null) failed = true
        }
        // 上报加载状态给 shared 单元格 (对齐 app 端 onStateChange, 失败时单元格显示"重新加载")
        LaunchedEffect(bitmap, failed) {
            onLoadState(
                when {
                    bitmap != null -> MangaCellState.SUCCESS
                    failed -> MangaCellState.ERROR
                    else -> MangaCellState.LOADING
                }
            )
        }

        val bmp = bitmap
        // 字节级下载进度: 监听 [OhosDownloadProgressEvents] (ArkTS @ohos.net.http
        // requestInStream dataReceive 事件经 platformEvent 通道回传), 转发给 shared 单元格
        // 转圈环心, 与 desktop/app 端 ProgressManager 同链路 (百分比/KB 格式一致)
        val currentOnProgress by rememberUpdatedState(onProgress)
        DisposableEffect(url) {
            OhosDownloadProgressEvents.addListener(url) { _, percentage, bytesRead, totalBytes ->
                currentOnProgress(
                    if (percentage > 0) {
                        "$percentage%"
                    } else {
                        val kb = bytesRead / 1024.0
                        if (kb >= 1024) {
                            // native 无 String.format (JVM-only), 手动保留 1 位小数
                            val mb = (kb / 1024.0 * 10).roundToInt() / 10.0
                            "${mb}MB"
                        } else {
                            "${kb.toInt()}KB"
                        }
                    }
                )
            }
            onDispose { OhosDownloadProgressEvents.removeListener(url) }
        }
        // 合并颜色滤镜: colorFilterConfig 矩阵 + 灰度矩阵 (与 iOS/desktop/app 端同矩阵同顺序)
        val colorFilter = remember(colorFilterConfig, grayEnabled) {
            mangaColorFilter(colorFilterConfig, grayEnabled)
        }
        if (bmp != null) {
            // 等比渲染: 按位图固有宽高比显式定高 (与 desktop/iOS 同一修复,
            // 纵向永不变形; 横向仍整页铺满视口等比留白, 行为不变)
            val aspect = if (bmp.width > 0 && bmp.height > 0) {
                Modifier.aspectRatio(bmp.width.toFloat() / bmp.height)
            } else {
                Modifier
            }
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = if (horizontal) modifier else modifier.then(aspect),
                contentScale = ContentScale.Fit,
                colorFilter = colorFilter,
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
    // 保存图片到沙盒导出路径 (destPath 由 shared 路由经 PlatformServiceProviders.files.saveFile
    // 取得, 鸿蒙的 saveFile 返回 filesDir/export 可写路径): 取字节复用 [MangaImageBytesLoader]
    // (图片缓存 → 本地书 FileBook → 按书源下载+解密), 与 desktop/iOS/app 端同一条链路
    override suspend fun saveImage(
        url: String,
        book: Book?,
        source: BookSource?,
        destPath: String
    ): Boolean = withContext(IoDispatcher) {
        book ?: return@withContext false
        runCatching {
            val bytes = MangaImageBytesLoader.load(url, book, source, currentCoroutineContext())
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
