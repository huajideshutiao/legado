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
 * Skia 三端共用的漫画动图控制器：翻页判定全部在共享 [MangaGifAutoNextPlayer] 状态机，
 * 本类只提供帧表渲染侧的三个原子操作 (armed 态由状态机持有, 帧推进循环恒转, LOOP 态
 * 播完回调被状态机忽略)。
 */
class MangaAnimatedImageRenderer : MangaGifAutoNextPlayer(), MangaRenderState.MangaPageRenderer {

    var frameIndex by mutableIntStateOf(0)
        internal set
    var restartToken by mutableIntStateOf(0)
        private set

    override fun enterPlayOnce() = Unit

    override fun enterLoopForever() = Unit

    override fun restartFromFirstFrame() {
        frameIndex = 0
        restartToken++
    }

    override fun playGifForCurrentPage() = playForCurrentPage()

    override fun stopGifAutoNext() = stopAutoNext()

    internal fun resetPlayback() = onNewImageLoaded()

    /** 覆盖停稳回调先于图片加载完成的时序。 */
    internal fun onFramesReady() {
        if (shouldArmOnLoaded()) playForCurrentPage() else stopAutoNext()
    }

    /** 播完一轮：翻页/滑走/受阻重播全交共享状态机。 */
    internal fun onFrameLoopFinished() = onPlayOnceFinished()
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
