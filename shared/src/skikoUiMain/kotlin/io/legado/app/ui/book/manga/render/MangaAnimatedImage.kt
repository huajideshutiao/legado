package io.legado.app.ui.book.manga.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.image.AnimatedFrames
import io.legado.app.help.image.DecodedBitmapCache
import io.legado.app.help.image.DecodedImageResult
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.image.decodeImageAuto
import io.legado.app.ui.book.manga.LocalMangaGifSlot
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.ui.book.manga.mangaColorFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private sealed interface MangaSkiaImageState {
    data object Loading : MangaSkiaImageState
    data class Static(val bitmap: ImageBitmap) : MangaSkiaImageState
    data class Animated(val frames: AnimatedFrames) : MangaSkiaImageState
    data object Error : MangaSkiaImageState
}

/** 漫画页缓存 key (仅本地书内嵌图用; 网络页的 key 由平台图片加载器维护)。 */
internal fun mangaCacheKey(url: String, source: BookSource?): String =
    DecodedBitmapCache.cacheKey(url, source?.bookSourceUrl, isCover = false)

private fun DecodedImageResult.toState(): MangaSkiaImageState = when (this) {
    is DecodedImageResult.Static -> MangaSkiaImageState.Static(bitmap)
    is DecodedImageResult.Animated -> MangaSkiaImageState.Animated(frames)
}

/**
 * 本地书内嵌图 (cbz:// / file:// / 绝对路径): 网络加载器的 fetcher 只认网络页, 故直接
 * 取字节解码, 结果进 [DecodedBitmapCache]。
 */
private suspend fun loadLocalMangaPage(
    url: String,
    book: Book,
    source: BookSource?,
): DecodedImageResult? {
    val key = mangaCacheKey(url, source)
    DecodedBitmapCache.get(key)?.let { return DecodedImageResult.Static(it) }
    val bytes = withContext(IoDispatcher) {
        runCatching { ImageBitmapLoader().loadBytes(url, book, source) }.getOrNull()
    }
    if (bytes == null || bytes.isEmpty()) return null
    val decoded = withContext(Dispatchers.Default) { decodeImageAuto(bytes) } ?: return null
    if (decoded is DecodedImageResult.Static) DecodedBitmapCache.put(key, decoded.bitmap)
    return decoded
}

/**
 * 下载进度文本 (Skia 三端共用; 对照原版 MangaPageImageView.onProgress → 转圈环心百分比)。
 *
 * 有总长按百分比, 没有 (chunked / 服务端不给 Content-Length) 就按已下载量。
 */
fun mangaProgressText(bytesRead: Long, totalBytes: Long): String {
    if (totalBytes > 0) return "${(bytesRead * 100 / totalBytes).coerceIn(0, 100)}%"
    val kb = bytesRead / 1024.0
    if (kb < 1024) return "${kb.toInt()}KB"
    return "${(kb / 1024.0 * 10).roundToInt() / 10.0}MB"
}

/** Skia 三端共用的漫画动图控制器，行为对齐原版 GIF 自动翻页。 */
class MangaAnimatedImageRenderer : MangaRenderState.MangaPageRenderer {
    private enum class PlayMode { LOOP, ONCE }

    var frameIndex by mutableIntStateOf(0)
        internal set
    var restartToken by mutableIntStateOf(0)
        private set

    private var playMode by mutableStateOf(PlayMode.LOOP)
    private var turnConsumed = false

    var enabled: () -> Boolean = { false }
    var isArmTarget: () -> Boolean = { false }
    var onTurnPage: () -> Boolean = { false }

    override fun playGifForCurrentPage() {
        if (!enabled()) return
        turnConsumed = false
        playMode = PlayMode.ONCE
        // 停稳后必须从首帧开始，避免页面预布局期间已播到末帧。
        restartToken++
    }

    override fun stopGifAutoNext() {
        // 关闭自动翻页或离开居中页时恢复无限循环，不强制改变当前帧。
        playMode = PlayMode.LOOP
    }

    internal fun resetPlayback() {
        playMode = PlayMode.LOOP
        turnConsumed = false
        frameIndex = 0
        restartToken++
    }

    /** 覆盖停稳回调先于图片加载完成的时序。 */
    internal fun onFramesReady() {
        if (enabled() && isArmTarget()) {
            if (playMode != PlayMode.ONCE) playGifForCurrentPage()
        } else {
            stopGifAutoNext()
        }
    }

    /** 播完一轮后翻页；翻页受阻时保留 ONCE，下一轮从首帧再次尝试。 */
    internal fun onFrameLoopFinished() {
        if (playMode != PlayMode.ONCE) return
        if (turnConsumed || !isArmTarget()) {
            playMode = PlayMode.LOOP
            return
        }
        if (onTurnPage()) {
            turnConsumed = true
            playMode = PlayMode.LOOP
        }
    }
}

@Composable
private fun MangaAnimatedImage(
    frames: AnimatedFrames,
    renderer: MangaAnimatedImageRenderer,
    modifier: Modifier,
    colorFilter: ColorFilter?,
) {
    if (frames.frameCount == 0) return

    LaunchedEffect(frames, renderer.restartToken) {
        renderer.frameIndex = 0
        while (currentCoroutineContext().isActive) {
            for (index in 0 until frames.frameCount) {
                renderer.frameIndex = index
                delay(frames.durationsMs[index].toLong().coerceAtLeast(1L))
            }
            renderer.onFrameLoopFinished()
        }
    }

    Image(
        bitmap = frames.frames[renderer.frameIndex.coerceIn(0, frames.frameCount - 1)],
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        colorFilter = colorFilter,
    )
}

/**
 * desktop/iOS/鸿蒙漫画图片槽：用各端都带有的 Skia Codec 支持 GIF 与动画 WebP，
 * 并接入“播完一轮翻页”。静态图也走同一份字节解码，避免 ImageIO/Coil 变体差异。
 *
 * 三端 `Platform.Image` 的差异只剩"订阅哪个下载进度注册表"，调色/渲染全在此处，
 * 不要再在各端复制一份 [mangaColorFilter] + 本函数的调用。
 */
@Composable
fun MangaSkiaImage(
    url: String,
    modifier: Modifier,
    horizontal: Boolean,
    book: Book?,
    source: BookSource?,
    colorFilterConfig: MangaColorFilterConfig,
    grayEnabled: Boolean,
    onLoadState: (MangaCellState) -> Unit,
    retryTick: Int,
) {
    val colorFilter = remember(colorFilterConfig, grayEnabled) {
        mangaColorFilter(colorFilterConfig, grayEnabled)
    }
    val gifSlot = LocalMangaGifSlot.current
    val renderer = remember(url) { MangaAnimatedImageRenderer() }
    val rendererGetter: () -> MangaRenderState.MangaPageRenderer? = remember(renderer) {
        { renderer }
    }

    // 注册表只保存稳定 getter；每次组合仅刷新三项闭包，防止旧页面回调使用旧位置。
    SideEffect {
        gifSlot?.let { slot ->
            renderer.enabled = slot.enabled
            renderer.isArmTarget = slot.isArmTarget
            renderer.onTurnPage = slot.onTurnPage
            slot.onRenderer(rendererGetter)
        }
    }

    val imageState by produceState<MangaSkiaImageState>(
        // 同步窥视缓存: 预载过的页在组合首帧就出图, 不闪一帧转圈
        initialValue = peekMangaPage(url, source)?.toState() ?: MangaSkiaImageState.Loading,
        url,
        retryTick,
    ) {
        val skipCache = retryTick > 0
        if (!skipCache) {
            peekMangaPage(url, source)?.let {
                value = it.toState()
                return@produceState
            }
        }
        value = MangaSkiaImageState.Loading
        if (book == null) {
            value = MangaSkiaImageState.Error
            return@produceState
        }
        val decoded = if (url.startsWith("http://") || url.startsWith("https://")) {
            // 网络页: 内存/磁盘缓存、请求去重、预载命中全交给平台图片加载器
            loadMangaPage(url, book, source, skipMemoryCache = skipCache)
        } else {
            // 本地书内嵌图 (cbz:// / file:// / 绝对路径): 不经网络加载器, 直接取字节解码
            loadLocalMangaPage(url, book, source)
        }
        value = decoded?.toState() ?: MangaSkiaImageState.Error
    }

    // 同步上报最新加载状态给单元格 (SideEffect 保证每次重组均与当前 imageState 同步, 避免 LaunchedEffect 滞后 1 帧引发转圈闪现)
    SideEffect {
        when (imageState) {
            MangaSkiaImageState.Loading -> onLoadState(MangaCellState.LOADING)
            is MangaSkiaImageState.Static,
            is MangaSkiaImageState.Animated -> onLoadState(MangaCellState.SUCCESS)
            MangaSkiaImageState.Error -> onLoadState(MangaCellState.ERROR)
        }
    }

    LaunchedEffect(imageState) {
        when (imageState) {
            MangaSkiaImageState.Loading -> renderer.resetPlayback()
            is MangaSkiaImageState.Static -> {}
            is MangaSkiaImageState.Animated -> renderer.onFramesReady()
            MangaSkiaImageState.Error -> {}
        }
    }

    when (val state = imageState) {
        MangaSkiaImageState.Loading,
        MangaSkiaImageState.Error -> Box(modifier.background(MangaReaderBackground))

        is MangaSkiaImageState.Static -> {
            val bitmap = state.bitmap
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = if (horizontal) modifier else modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height),
                contentScale = ContentScale.Fit,
                colorFilter = colorFilter,
            )
        }

        is MangaSkiaImageState.Animated -> {
            val bitmap = state.frames.frames.first()
            MangaAnimatedImage(
                frames = state.frames,
                renderer = renderer,
                modifier = if (horizontal) modifier else modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height),
                colorFilter = colorFilter,
            )
        }
    }
}
