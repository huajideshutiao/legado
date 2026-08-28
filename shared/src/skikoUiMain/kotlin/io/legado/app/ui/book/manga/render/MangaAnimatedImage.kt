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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.image.AnimatedFrames
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.image.MangaImageBytesLoader
import io.legado.app.help.image.decodeAnimatedFrames
import io.legado.app.help.image.isAnimatedImageBytes
import io.legado.app.ui.book.manga.LocalMangaGifSlot
import io.legado.app.ui.book.manga.entities.MangaCellState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

private sealed interface MangaSkiaImageState {
    data object Loading : MangaSkiaImageState
    data class Static(val bitmap: ImageBitmap) : MangaSkiaImageState
    data class Animated(val frames: AnimatedFrames) : MangaSkiaImageState
    data object Error : MangaSkiaImageState
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
 */
@Composable
fun MangaSkiaImage(
    url: String,
    modifier: Modifier,
    horizontal: Boolean,
    book: Book?,
    source: BookSource?,
    colorFilter: ColorFilter?,
    onLoadState: (MangaCellState) -> Unit,
    retryTick: Int,
) {
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
        MangaSkiaImageState.Loading,
        url,
        retryTick,
    ) {
        value = MangaSkiaImageState.Loading
        if (book == null) {
            value = MangaSkiaImageState.Error
            return@produceState
        }
        val bytes = withContext(IoDispatcher) {
            runCatching {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    MangaImageBytesLoader.load(url, book, source, currentCoroutineContext())
                } else {
                    ImageBitmapLoader().loadBytes(url, book, source)
                }
            }.getOrNull()
        }
        if (bytes == null || bytes.isEmpty()) {
            value = MangaSkiaImageState.Error
            return@produceState
        }

        value = withContext(Dispatchers.Default) {
            val animated = if (isAnimatedImageBytes(bytes)) decodeAnimatedFrames(bytes) else null
            if (animated != null) {
                MangaSkiaImageState.Animated(animated)
            } else {
                val bitmap = runCatching {
                    SkiaImage.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
                }.getOrNull()
                if (bitmap != null) MangaSkiaImageState.Static(bitmap)
                else MangaSkiaImageState.Error
            }
        }
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
