package io.legado.app.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.image.loadCbzEntryBytes
import io.legado.app.help.image.ohosDecodeImageBytes
import io.legado.app.help.image.ohosDownloadImageBytes
import io.legado.app.help.source.SourceHelp
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 鸿蒙端 BookCover 加载组件 (对照 iOS [IosBookCover.kt] / 桌面端 DesktopBookCover 结构)。
 *
 * 加载链: 内存 LRU → 本地路径直接 Skia 解码 → 网络图先查 covers 磁盘缓存,
 * 未命中经 [ohosDownloadImageBytes] 下载 (带书源防盗链 header + coverDecodeJs 解密) 后落盘再解码。
 * `cbz://` 封面经 [loadCbzEntryBytes] 抽压缩包条目字节再解码 (本地漫画书)。
 * 失败/无封面回退默认封面样式 (与 iOS/desktop 占位视觉一致)。
 */

/** 详情页顶部模糊封面背景 (对照 iOS IosBlurCoverBg / 桌面 DesktopBookCover.BlurCoverBg)。 */
@Composable
fun OhosBlurCoverBg(book: Book?, modifier: Modifier = Modifier) {
    val bitmap = rememberOhosCoverBitmap(book?.getDisplayCover(), book)
    Box(modifier) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(24.dp),
                contentScale = ContentScale.Crop,
            )
        }
        // accent 半透明遮罩; 加载中/失败时兼作占位色 (与 iOS/desktop 视觉一致)
        Box(Modifier.fillMaxSize().background(Color(0xFF165DFF).copy(alpha = 0.15f)))
    }
}

/** 书籍封面 (对照 iOS IosInfoCover): 成功 Crop + 4dp 圆角, 失败回退书名首字 + accent 底。 */
@Composable
fun OhosInfoCover(book: Book?, modifier: Modifier = Modifier) {
    val bitmap = rememberOhosCoverBitmap(book?.getDisplayCover(), book)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = book?.name,
            modifier = modifier.clip(DesignTokens.shapeSm),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier
                .clip(DesignTokens.shapeSm)
                .background(Color(0xFF165DFF)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = book?.name?.firstOrNull()?.toString() ?: "?",
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 简介内整宽插图 (对照 iOS IosIntroImage / 桌面 DesktopBookCover.IntroImage)。 */
@Composable
fun OhosIntroImage(src: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val imageLabel = rememberString("image_label_with_src", src)
    val bitmap = rememberOhosCoverBitmap(src.ifBlank { null }, null)
    val baseModifier = modifier
        .fillMaxWidth()
        .height(120.dp)
        .clip(DesignTokens.shapeSm)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = baseModifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            baseModifier.background(Color(0xFFEEEEEE)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = imageLabel,
                color = Color(0xFF666666),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
    // onClick 暂未挂到节点 (与 iOS/desktop 一致, 后续接入大图查看器时挂 combinedClickable)
    @Suppress("UNUSED_PARAMETER")
    val _unused = onClick
}

/**
 * 加载封面为 [ImageBitmap] 状态 (produceState, key = [coverUrl]); 初值取内存缓存避免重组闪占位。
 * [book] 用于书源防盗链 header 解析与 isLocal 判定, 可为 null。
 */
@Composable
fun rememberOhosCoverBitmap(coverUrl: String?, book: Book?): ImageBitmap? {
    val bitmap by produceState(coverUrl?.let(::peekOhosCoverCache), coverUrl) {
        if (!coverUrl.isNullOrEmpty()) {
            value = loadOhosCoverBitmap(coverUrl, book)
        }
    }
    return bitmap
}

/** 封面内存 LRU 上限 (与桌面端 DesktopBookCoverCache 一致)。 */
private const val COVER_CACHE_MAX_SIZE = 200

/**
 * 封面内存 LRU (访问序 LinkedHashMap + synchronized, 模式对照 NativeQuickJsSharedJsScopeProvider.LruScopeCache)。
 * value 含失败 null (与桌面端一致, 避免坏 URL 在列表滚动中反复重试)。
 */
private val coverCacheLock = Any()
private val coverCache = LinkedHashMap<String, ImageBitmap?>()

private class CachePeek(val hit: Boolean, val value: ImageBitmap?)

private fun cacheGet(key: String): CachePeek = synchronized(coverCacheLock) {
    if (!coverCache.containsKey(key)) return@synchronized CachePeek(false, null)
    val v = coverCache.remove(key)
    coverCache[key] = v
    CachePeek(true, v)
}

private fun cachePut(key: String, value: ImageBitmap?): Unit = synchronized(coverCacheLock) {
    coverCache.remove(key)
    coverCache[key] = value
    if (coverCache.size > COVER_CACHE_MAX_SIZE) {
        coverCache.remove(coverCache.keys.first())
    }
}

/** 同步窥探内存缓存 (produceState 初值用, 命中则首帧即出图)。 */
fun peekOhosCoverCache(coverUrl: String): ImageBitmap? = cacheGet(coverUrl).value

/**
 * 加载封面 (挂起): 内存 LRU → 本地路径解码 → 磁盘缓存解码 → 下载+落盘+解码; 失败缓存 null。
 */
suspend fun loadOhosCoverBitmap(coverUrl: String, book: Book?): ImageBitmap? {
    cacheGet(coverUrl).let { if (it.hit) return it.value }
    val bitmap = withContext(Dispatchers.IO) {
        runCatching {
            when {
                coverUrl.startsWith("file://") -> decodeLocalFile(coverUrl.removePrefix("file://"))
                coverUrl.startsWith("/") -> decodeLocalFile(coverUrl)
                coverUrl.startsWith("http://") || coverUrl.startsWith("https://") ->
                    loadHttpCover(coverUrl, book)
                // 本地 cbz 漫画封面: 抽压缩包条目字节再 Skia 解码
                // (cbz://{path}#{entry} 自含 / cbz://{entry} + book.bookUrl 两种形式)
                coverUrl.startsWith("cbz://") ->
                    loadCbzEntryBytes(coverUrl, book?.bookUrl)?.let(::ohosDecodeImageBytes)
                else -> null // 相对路径 / 未知协议, 走占位
            }
        }.getOrNull()
    }
    cachePut(coverUrl, bitmap)
    return bitmap
}

private fun decodeLocalFile(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.exists()) return null
    return runCatching { file.readBytes() }.getOrNull()?.let(::ohosDecodeImageBytes)
}

/** 网络封面: covers 磁盘缓存命中直接解码, 否则下载 (带书源防盗链 header + coverDecodeJs 解密) 解码成功后落盘。 */
private suspend fun loadHttpCover(url: String, book: Book?): ImageBitmap? {
    val cachePath = "${coverCacheDirPath()}/${MD5Utils.md5Encode16(url)}.img"
    // 缓存损坏/不可解码时落到下载分支, writeBytes 直接覆盖 (落盘的是解密后字节, 命中即直接解码)
    decodeLocalFile(cachePath)?.let { return it }
    val source = book?.origin
        ?.let { runCatching { SourceHelp.getSource(it) }.getOrNull() } as? BookSource
    val bytes = ohosDownloadImageBytes(url, book, source, isCover = true) ?: return null
    val bitmap = ohosDecodeImageBytes(bytes) ?: return null
    // 解码成功才落盘 (避免缓存 HTML 报错页等坏字节)
    runCatching {
        val cacheFile = File(cachePath)
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeBytes(bytes)
    }
    return bitmap
}

/** 网络封面磁盘缓存目录: `{filesDir}/covers/cache` (与 OhosCoverConfigScreen 的 covers 落盘同根)。 */
private fun coverCacheDirPath(): String {
    return "${AppFilesDirs.get().filesDir.trimEnd('/')}/covers/cache"
}
