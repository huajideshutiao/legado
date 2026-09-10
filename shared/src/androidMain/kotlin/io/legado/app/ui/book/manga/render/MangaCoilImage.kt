package io.legado.app.ui.book.manga.render

import android.graphics.drawable.Animatable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import coil3.asDrawable
import coil3.gif.MovieDrawable
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.ScaleDrawable
import coil3.size.Size
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.help.image.mangaPageCacheKey
import io.legado.app.model.manga.MangaModel
import io.legado.app.ui.book.manga.LocalMangaGifSlot
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.ui.book.manga.mangaColorFilter
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Size as PainterSize

private sealed interface MangaCoilState {
    data object Loading : MangaCoilState
    data class Success(val drawable: Drawable) : MangaCoilState
    data object Error : MangaCoilState
}

/**
 * Drawable → Compose Painter：订阅 [Drawable.Callback] 的失效回调驱动重绘
 * (accompanist-drawablepainter 同款模式，避免为单一消费点引入新依赖)。
 *
 * 动图 (AnimatedImageDrawable/MovieDrawable) 每推进一帧调 invalidateSelf → 这里 tick++
 * → 重画，对 Drawable 的播放控制 (repeatCount/stop/start) 因此能真实反映到 Compose。
 * 播放启停由 [MangaDrawableGifPlayer] 与 [MangaCoilImage] 的 DisposableEffect 负责，
 * 本 painter 只做"画 + 重绘订阅"。
 */
private class MangaDrawablePainter(private val drawable: Drawable) : Painter() {

    private var invalidateTick by mutableIntStateOf(0)

    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            invalidateTick++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit

        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }

    init {
        drawable.callback = callback
    }

    override val intrinsicSize: PainterSize
        get() {
            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight
            return if (w <= 0 || h <= 0) PainterSize.Unspecified else PainterSize(w.toFloat(), h.toFloat())
        }

    override fun DrawScope.onDraw() {
        // 读取 tick 建立失效订阅：动图每帧 invalidate 都会触发重绘
        invalidateTick
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}

/**
 * Android 端「播完一轮翻页」状态机 ([MangaGifAutoNextPlayer]) 的 Drawable 实现：
 * 三个原子操作直接作用在 Coil3 解码出的动图 Drawable 实例上（与原 MangaPageImageView
 * 相同的实例控制方式）。
 *
 * 关键：**绝不**调用 clearAnimationCallbacks——平台 AnimatedImageDrawable 在动画结束时
 * 会向主线程投递一个遍历回调列表的 Runnable，期间清空列表会空指针崩溃。只注册不清除：
 * 无限循环时回调本就不触发，单轮时才触发一次。
 */
private class MangaDrawableGifPlayer(
    private val drawableProvider: () -> Drawable?,
) : MangaGifAutoNextPlayer(), MangaRenderState.MangaPageRenderer {

    // 已注册结束回调的动图实例，确保每个实例只注册一次
    private var callbackTarget: Drawable? = null

    // 待忽略的"自触发结束回调"计数：平台 AnimatedImageDrawable.stop() 会主动投递一次
    // onAnimationEnd，重播时主动 stop 不应被误判为自然播完一轮，故逐一抵消
    private var pendingSelfEnds = 0

    override fun enterPlayOnce() {
        when (val d = animatedDrawable()) {
            is MovieDrawable -> d.setRepeatCount(0)
            is AnimatedImageDrawable -> d.repeatCount = 0
        }
        ensureEndCallback()
    }

    override fun enterLoopForever() {
        val d = animatedDrawable() ?: return
        when (d) {
            is MovieDrawable -> d.setRepeatCount(MovieDrawable.REPEAT_INFINITE)
            is AnimatedImageDrawable -> d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
        // 离开居中页恢复循环时确保在播（原版 disarmGifAutoNext 语义）
        (d as? Animatable)?.takeIf { !it.isRunning }?.start()
    }

    override fun restartFromFirstFrame() {
        when (val d = animatedDrawable()) {
            is MovieDrawable -> {
                if (d.isRunning) d.stop()
                d.start()
            }

            is AnimatedImageDrawable -> {
                if (d.isRunning) {
                    pendingSelfEnds++
                    d.stop()
                }
                d.start()
            }
        }
    }

    override fun playGifForCurrentPage() {
        if (animatedDrawable() == null) return
        playForCurrentPage()
    }

    override fun stopGifAutoNext() {
        if (animatedDrawable() == null) return
        stopAutoNext()
    }

    /** 结束回调入口（只可能来自 [callbackTarget]）；校验仍是当前页的实例后交状态机。 */
    private fun onAnimationEnd(drawable: Drawable) {
        // 回调可能在单元格已复用/换图后才到达（经 animatedDrawable 对外层 ScaleDrawable 解包后比对）
        if (animatedDrawable() !== drawable) return
        if (pendingSelfEnds > 0) {
            pendingSelfEnds--
            return
        }
        onPlayOnceFinished()
    }

    private fun ensureEndCallback() {
        val d = animatedDrawable() ?: return
        if (callbackTarget === d) return
        callbackTarget = d
        pendingSelfEnds = 0
        when (d) {
            is MovieDrawable ->
                d.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
                    override fun onAnimationEnd(d: Drawable) =
                        this@MangaDrawableGifPlayer.onAnimationEnd(d)
                })

            is AnimatedImageDrawable ->
                d.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(d: Drawable) =
                        this@MangaDrawableGifPlayer.onAnimationEnd(d)
                })
        }
    }

    /** Coil 的动画 WebP 会被 ScaleDrawable 包裹，控制循环/判动图前先取内层。 */
    private fun animatedDrawable(): Drawable? {
        var d = drawableProvider()
        while (d != null) {
            when {
                d is MovieDrawable -> return d
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable -> return d
                d is ScaleDrawable -> d = d.child
                else -> return null
            }
        }
        return null
    }
}

/**
 * Android 漫画图片槽（纯 Compose，替代原 AndroidView 包裹的 MangaPageImageView）：
 *
 * - 加载走共享 Coil3 SingletonImageLoader（防盗链 header/BookHelp 缓存取字节均已在
 *   fetcher 层注册），显式 [mangaPageCacheKey] 与预载请求同 key，预载必命中；
 *   Size.ORIGINAL 与 desktop/iOS 的 MangaPageCoil 同参（isSampled=false，对任意显示尺寸有效）
 * - 预载命中：组合首帧同步窥视内存缓存直接出图，不闪一帧转圈（对齐 skiko peekMangaPage）
 * - GIF/动画 WebP：Drawable 实例交给 [MangaDrawableGifPlayer]，播完一轮自动翻页
 * - 灰度/调色：绘制期 [mangaColorFilter]，与 skiko 三端同源（解码期 Transformation 会让
 *   动图退化首帧，不再使用）
 */
@Composable
fun MangaCoilImage(
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
    val sourceKey = source?.bookSourceUrl
    val colorFilter = remember(colorFilterConfig, grayEnabled) {
        mangaColorFilter(colorFilterConfig, grayEnabled)
    }
    val context = LocalContext.current
    val imageLoader = coil3.SingletonImageLoader.get(context)
    val gifSlot = LocalMangaGifSlot.current
    val currentOnProgress by rememberUpdatedState(onProgress)

    // 下载进度订阅（对照原 MangaPageImageView.doLoad 的 ProgressManager.addListener）
    DisposableEffect(url, sourceKey) {
        val removeListener = ProgressManager.addListener(url) { _, _, bytesRead, totalBytes ->
            currentOnProgress(mangaProgressText(bytesRead, totalBytes))
        }
        onDispose { removeListener() }
    }

    // 预载命中：同步窥视内存缓存，组合首帧直接出图（重试时跳过缓存读）
    val peeked = remember(url, retryTick, sourceKey) {
        if (retryTick > 0) {
            null
        } else {
            imageLoader.memoryCache
                ?.get(MemoryCache.Key(mangaPageCacheKey(url, source)))
                ?.image
                ?.asDrawable(context.resources)
        }
    }
    var state by remember(url, retryTick, sourceKey) {
        mutableStateOf<MangaCoilState>(
            if (peeked != null) MangaCoilState.Success(peeked) else MangaCoilState.Loading
        )
    }

    // 未命中才发起请求（peek 命中时不发请求，省一次 Engine 调度）
    if (peeked == null) {
        LaunchedEffect(url, retryTick, book, sourceKey) {
            if (book == null) {
                state = MangaCoilState.Error
                return@LaunchedEffect
            }
            state = MangaCoilState.Loading
            val request = ImageRequest.Builder(context)
                .data(MangaModel(url, book, source))
                .memoryCacheKey(mangaPageCacheKey(url, source))
                // 磁盘缓存禁用 (loadManga 已自管 BookHelp 磁盘缓存)；重试跳过内存缓存读但仍写回
                .memoryCachePolicy(if (retryTick > 0) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .size(Size.ORIGINAL)
                .build()
            val result = try {
                imageLoader.execute(request)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }
            state = (result as? SuccessResult)?.image
                ?.asDrawable(context.resources)
                ?.let { MangaCoilState.Success(it) }
                ?: MangaCoilState.Error
        }
    }

    val player = remember(url, sourceKey) {
        MangaDrawableGifPlayer { (state as? MangaCoilState.Success)?.drawable }
    }

    // GIF 播完翻页接线（对照 skiko MangaSkiaImage 的 SideEffect 模式）
    SideEffect {
        gifSlot?.let { slot ->
            player.enabled = slot.enabled
            player.isArmTarget = slot.isArmTarget
            player.onTurnPage = slot.onTurnPage
            slot.onRenderer { player }
        }
    }

    // 新图就绪/装填判定（对照原 MangaPageImageView.onSuccess 与 skiko onFramesReady）
    LaunchedEffect(state) {
        when (val s = state) {
            is MangaCoilState.Success ->
                if (s.drawable.isAnimated()) {
                    if (player.shouldArmOnLoaded()) player.playForCurrentPage()
                    else player.stopAutoNext()
                }

            else -> Unit
        }
    }

    // 同步上报加载状态给单元格（SideEffect 与当前 state 同帧，避免转圈闪现）
    SideEffect {
        onLoadState(
            when (state) {
                MangaCoilState.Loading -> MangaCellState.LOADING
                is MangaCoilState.Success -> MangaCellState.SUCCESS
                MangaCoilState.Error -> MangaCellState.ERROR
            }
        )
    }

    when (val s = state) {
        MangaCoilState.Loading,
        MangaCoilState.Error,
        -> Box(modifier.background(MangaReaderBackground))

        is MangaCoilState.Success -> {
            val drawable = s.drawable
            // 纵向：宽度铺满 + 按图宽高比撑高（原版 adjustViewBounds 语义）；横向：填满单元格 Fit 居中
            val imageModifier = if (horizontal) {
                modifier
            } else {
                val w = drawable.intrinsicWidth
                val h = drawable.intrinsicHeight
                if (w > 0 && h > 0) modifier.aspectRatio(w.toFloat() / h) else modifier
            }
            DisposableEffect(drawable) {
                (drawable as? Animatable)?.start()
                onDispose {
                    (drawable as? Animatable)?.stop()
                    drawable.callback = null
                }
            }
            Image(
                painter = remember(drawable) { MangaDrawablePainter(drawable) },
                contentDescription = null,
                modifier = imageModifier,
                contentScale = ContentScale.Fit,
                colorFilter = colorFilter,
            )
        }
    }
}

private fun Drawable.isAnimated(): Boolean {
    var d: Drawable? = this
    while (d != null) {
        when {
            d is MovieDrawable -> return true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable -> return true
            d is ScaleDrawable -> d = d.child
            else -> return false
        }
    }
    return false
}
