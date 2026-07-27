package io.legado.app.ui.book.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.image.isGifBytes
import io.legado.app.help.image.rememberAnimatedImageBitmap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 鸿蒙端漫画页渲染 (供 shared [MangaReaderScreenContent] 的 imageSlot 调用)。
 *
 * 对照 desktop DesktopMangaImage / app 端 MangaAdapter 语义:
 * - 当前页优先: [produceState] 随 cell 进入组合即加载, 命中 [mangaBitmapCache] 首帧出图
 * - 邻页预取: 当前页解码完成后后台预取后 [PREFETCH_AHEAD] 页 + 前 1 页 (仅进缓存, 不渲染)
 * - 横向 [ContentScale.Fit] 整页 / 竖向 [ContentScale.FillWidth] webtoon (与 desktop 一致)
 * - 加载中进度圈占位, 失败保持占位 (调用方黑底)
 *
 * 图片加载走 [ImageBitmapLoader] 鸿蒙实现 (本地/网络带书源防盗链 header + `cbz://` 压缩包条目)。
 * GIF 页经 [rememberAnimatedImageBitmap] 逐帧播放 (skiko Codec), 帧表不入 LRU 缓存。
 */
@Composable
fun OhosMangaImage(
    url: String,
    images: List<String>,
    book: Book?,
    bookSource: BookSource?,
    modifier: Modifier = Modifier,
    horizontal: Boolean,
) {
    val bitmap by produceState(peekMangaBitmap(url), url) {
        if (url.isBlank()) return@produceState
        if (value == null) value = loadOhosMangaBitmap(url, book, bookSource)
    }
    // GIF 动图旁路: 命中才多取一次字节 (漫画页 GIF 少见, 非 GIF 零额外解码开销);
    // 帧表不进 mangaBitmapCache — 逐帧位图体积是静态页的 N 倍, 会挤爆 12 页的 LRU 预算
    val gifBytes by produceState<ByteArray?>(null, url) {
        if (url.isBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { ImageBitmapLoader().loadBytes(url, book, bookSource) }.getOrNull()
        }?.takeIf { isGifBytes(it) }
    }
    val animatedFrame = rememberAnimatedImageBitmap(gifBytes)
    // 邻页预取: 当前页就绪后串行预取, 不与当前页争抢带宽 (对照 app 端 MangaAdapter 预取时机);
    // loadOhosMangaBitmap 先查缓存, 已加载页零开销
    LaunchedEffect(url, bitmap != null) {
        if (bitmap == null) return@LaunchedEffect
        val index = images.indexOf(url)
        if (index < 0) return@LaunchedEffect
        for (i in index + 1..minOf(index + PREFETCH_AHEAD, images.lastIndex)) {
            loadOhosMangaBitmap(images[i], book, bookSource)
        }
        images.getOrNull(index - 1)?.let { loadOhosMangaBitmap(it, book, bookSource) }
    }
    Box(
        modifier.background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = animatedFrame ?: bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = if (horizontal) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                contentScale = if (horizontal) ContentScale.Fit else ContentScale.FillWidth,
            )
        } else {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 4.dp,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

/** 邻页预取页数 (向后, 向前固定 1 页; 漫画页解码后内存占用大, 不追平 app 端 Glide 预取量)。 */
private const val PREFETCH_AHEAD = 2

/** 漫画页内存 LRU 上限 (页图远大于封面, 故远小于 OhosBookCover 的 200)。 */
private const val MANGA_CACHE_MAX_SIZE = 12

// 访问序 LRU (remove+put 手工维护, LinkedHashMap 无 accessOrder 构造器);
// 锁用 atomicfu SynchronizedObject (native 无 kotlin.synchronized(Any), 对照 MangaReaderViewModelShared)
private val mangaCacheLock = SynchronizedObject()
private val mangaBitmapCache = LinkedHashMap<String, ImageBitmap>()

/** 同步窥探内存缓存 (produceState 初值用, 命中则首帧即出图, 滚回不闪占位)。 */
private fun peekMangaBitmap(url: String): ImageBitmap? = synchronized(mangaCacheLock) {
    mangaBitmapCache.remove(url)?.also { mangaBitmapCache[url] = it }
}

private fun putMangaBitmap(url: String, bitmap: ImageBitmap) = synchronized(mangaCacheLock) {
    mangaBitmapCache.remove(url)
    mangaBitmapCache[url] = bitmap
    if (mangaBitmapCache.size > MANGA_CACHE_MAX_SIZE) {
        mangaBitmapCache.remove(mangaBitmapCache.keys.first())
    }
}

/**
 * 加载漫画页 (挂起): 内存 LRU → [ImageBitmapLoader] (本地 / 网络带书源 header / `cbz://` 条目)。
 * 失败返回 null 且不进缓存 (与封面缓存 null 的策略相反: 漫画页可重试)。
 */
internal suspend fun loadOhosMangaBitmap(
    url: String,
    book: Book?,
    bookSource: BookSource?,
): ImageBitmap? {
    peekMangaBitmap(url)?.let { return it }
    val bitmap = withContext(Dispatchers.IO) {
        runCatching { ImageBitmapLoader().loadBitmap(url, book, bookSource) }.getOrNull()
    }
    return bitmap?.also { putMangaBitmap(url, it) }
}
