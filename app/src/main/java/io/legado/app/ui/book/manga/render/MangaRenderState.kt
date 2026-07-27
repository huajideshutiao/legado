package io.legado.app.ui.book.manga.render

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.lerp
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.MangaModel
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.read.config.ClickArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 漫画渲染层状态：列表数据/缩放平移(原 WebtoonFrame)/自动滚动(原 ScrollTimer)/
 * Glide 预加载(原 RecyclerViewPreloader)/GIF 装填注册表。
 * scale/trans 只被 graphicsLayer 块读取，缩放平移不触发重组与列表重测量。
 */
class MangaRenderState {

    val listState = LazyListState()
    val clickArea = ClickArea()

    var items by mutableStateOf<List<BaseMangaPage>>(emptyList())
    var horizontal by mutableStateOf(false)
    var colorFilterConfig by mutableStateOf(MangaColorFilterConfig())
    var grayEnabled by mutableStateOf(false)
    var gifAutoNext = false

    var book: Book? = null
    var bookSource: BookSource? = null

    // ---- 由 Activity 注入的回调 ----
    var onAction: (Int) -> Unit = {}
    var onLongTap: () -> Boolean = { false }
    var onCenterItemChanged: (Int) -> Unit = {}
    var onScrollIdle: () -> Unit = {}
    var onAutoPageTick: () -> Unit = {}
    var onGifTurnPage: () -> Boolean = { false }

    // ---- 组合期注入 ----
    internal var scope: CoroutineScope? = null
    internal var flingBehavior: FlingBehavior? = null
    internal var decaySpec: DecayAnimationSpec<Float>? = null

    // ---- 缩放/平移(原 WebtoonFrame) ----
    var scale by mutableFloatStateOf(DEFAULT_RATE)
        private set
    var transX by mutableFloatStateOf(0f)
        private set
    var transY by mutableFloatStateOf(0f)
        private set

    private var containerSize = IntSize.Zero
    private var zoomAnimJob: Job? = null
    private var panFlingJob: Job? = null

    // 是否允许拖动翻页：上一次 pan 在边界处松手后，下一次拖动才允许翻页
    private var allowPageScroll = false

    fun onContainerSize(size: IntSize) {
        if (size == containerSize) return
        containerSize = size
        clickArea.setRect(size.width, size.height)
        // 尺寸变化（旋转）时按新 clamp 重新约束 translation，避免放大态画面飞出屏幕
        applyClampedTranslation()
    }

    private fun clampMaxX(): Float =
        ((containerSize.width / 2f) * (scale - DEFAULT_RATE)).coerceAtLeast(0f)

    private fun clampMaxY(): Float =
        ((containerSize.height / 2f) * (scale - DEFAULT_RATE)).coerceAtLeast(0f)

    /** 标准图库钳制：图片边不越过屏幕边；scale ≤ 1 时上限为 0，强制居中 */
    private fun applyClampedTranslation() {
        transX = transX.coerceIn(-clampMaxX(), clampMaxX())
        transY = transY.coerceIn(-clampMaxY(), clampMaxY())
    }

    private fun isAtDefaultScale(): Boolean = abs(scale - DEFAULT_RATE) < SCALE_EPSILON

    private fun isAtBoundary(): Boolean {
        // clampMax < 1f 视为该轴没有可平移空间，避免 scale≈1 时 trans≈0 命中“边界”
        val cmx = clampMaxX()
        val cmy = clampMaxY()
        return (cmx >= 1f && abs(abs(transX) - cmx) < 1f) ||
            (cmy >= 1f && abs(abs(transY) - cmy) < 1f)
    }

    private fun animateTo(toScale: Float, toX: Float, toY: Float) {
        zoomAnimJob?.cancel()
        zoomAnimJob = scope?.launch {
            val s0 = scale
            val x0 = transX
            val y0 = transY
            // cancel 时保持当前帧值不 snap 到终点，避免用户中途上手 pan 出现闪跳
            animate(0f, 1f, animationSpec = tween(ANIM_DURATION, easing = DecelerateEasing)) { t, _ ->
                scale = lerp(s0, toScale, t)
                transX = lerp(x0, toX, t)
                transY = lerp(y0, toY, t)
            }
        }
    }

    fun resetZoom() {
        animateTo(DEFAULT_RATE, 0f, 0f)
        allowPageScroll = false
    }

    // ---- 手势入口(由 webtoonGestures 调用) ----

    internal fun beginPinch() {
        // 抢占动画与翻页惯性的写入权
        zoomAnimJob?.cancel()
        panFlingJob?.cancel()
    }

    /** 锚 prevFocus 那一点经缩放后落到当前 focus 下；zoom==1 时退化为纯双指拖动 */
    internal fun pinchBy(prevFocus: Offset, focus: Offset, zoom: Float) {
        val nextScale = (scale * zoom).coerceIn(MIN_RATE, MAX_RATE)
        val ratio = nextScale / scale
        val hw = containerSize.width / 2f
        val hh = containerSize.height / 2f
        transX = focus.x - hw - (prevFocus.x - hw - transX) * ratio
        transY = focus.y - hh - (prevFocus.y - hh - transY) * ratio
        scale = nextScale
        applyClampedTranslation()
    }

    internal fun endPinch() {
        // 缩小到 1× 以下时弹回 1×，实现橡皮筋手感
        if (scale < DEFAULT_RATE) resetZoom()
        allowPageScroll = false
    }

    internal fun canPan(): Boolean = scale > DEFAULT_RATE

    internal fun beginPan() {
        zoomAnimJob?.cancel()
        panFlingJob?.cancel()
        // 甩动惯性到达边界时，本次拖动即允许翻页（不需要再松手一次）
        if (!allowPageScroll) {
            allowPageScroll = isAtBoundary()
        }
    }

    /**
     * 单指 pan：上一次在边界处松手后(allowPageScroll)，拖向边界方向把余量喂给列表翻页，
     * 拖离边界方向切回平移；否则只平移钳制，必须松手后重新拖动才能翻页。
     */
    internal fun panBy(delta: Offset) {
        if (allowPageScroll) {
            val clampX = clampMaxX()
            val clampY = clampMaxY()
            val atRight = transX > clampX - 1f
            val atLeft = transX < -clampX + 1f
            val atBottom = transY > clampY - 1f
            val atTop = transY < -clampY + 1f
            val awayFromBoundary = (atRight && delta.x < 0) || (atLeft && delta.x > 0) ||
                (atBottom && delta.y < 0) || (atTop && delta.y > 0)
            if (awayFromBoundary) {
                allowPageScroll = false
                // 落到下方平移逻辑
            } else {
                // 拖向边界方向 → 滚动列表翻页；手指方向与滚动方向相反
                val d = if (horizontal) delta.x else delta.y
                listState.dispatchRawDelta(-d / scale)
                return
            }
        }
        transX += delta.x
        transY += delta.y
        applyClampedTranslation()
    }

    /**
     * 抬手判定：画面惯性在钳制范围内衰减(原 OverScroller)；手指速度反号喂给列表
     * fling(原 rv.fling)——横向由 snap 决定翻不翻并归位，纵向为普通滚动。
     * 已到边界时置 allowPageScroll，下一次拖动才允许连续拖动翻页。
     */
    internal fun finishPan(velocity: Velocity) {
        val atBoundary = isAtBoundary()
        panFling(velocity.x, velocity.y)
        val fb = flingBehavior
        val v = if (horizontal) velocity.x else velocity.y
        if (fb != null) {
            scope?.launch {
                listState.scroll { with(fb) { performFling(-v) } }
            }
        }
        allowPageScroll = atBoundary
    }

    private fun panFling(vx: Float, vy: Float) {
        val decay = decaySpec ?: return
        panFlingJob = scope?.launch {
            coroutineScope {
                launch {
                    AnimationState(transX, vx).animateDecay(decay) {
                        val clamped = value.coerceIn(-clampMaxX(), clampMaxX())
                        transX = clamped
                        if (clamped != value) cancelAnimation()
                    }
                }
                launch {
                    AnimationState(transY, vy).animateDecay(decay) {
                        val clamped = value.coerceIn(-clampMaxY(), clampMaxY())
                        transY = clamped
                        if (clamped != value) cancelAnimation()
                    }
                }
            }
        }
    }

    internal fun onSingleTap(pos: Offset) {
        // 用屏幕坐标判定九宫格，点击区域始终跟着屏幕走，与缩放/平移无关
        val action = clickArea.getAction(pos.x, pos.y)
        if (action != -1) onAction(action)
    }

    internal fun onDoubleTap(pos: Offset) {
        zoomAnimJob?.cancel()
        panFlingJob?.cancel() // 翻页惯性窗口内双击会被惯性反向覆写 trans，必须先停
        allowPageScroll = false
        if (!isAtDefaultScale()) {
            animateTo(DEFAULT_RATE, 0f, 0f)
        } else {
            val toScale = DOUBLE_TAP_SCALE
            // 锚定双击位置：放大后该屏幕点继续位于该屏幕点之下
            val toX = (containerSize.width / 2f - pos.x) * (toScale - 1f)
            val toY = (containerSize.height / 2f - pos.y) * (toScale - 1f)
            animateTo(toScale, toX, toY)
        }
    }

    // ---- 列表定位/翻页(原 Activity 直调 RV) ----

    fun centerItemIndex(): Int {
        val li = listState.layoutInfo
        val center = (li.viewportStartOffset + li.viewportEndOffset) / 2
        return li.visibleItemsInfo.firstOrNull { center >= it.offset && center < it.offset + it.size }
            ?.index ?: -1
    }

    fun canScroll(direction: Int): Boolean =
        if (direction > 0) listState.canScrollForward else listState.canScrollBackward

    /** 待应用的定位请求：由组合内 LaunchedEffect 在新 items 组合落定后执行 */
    internal class PendingScroll(val index: Int, val onApplied: (() -> Unit)?)

    internal var pendingScroll by mutableStateOf<PendingScroll?>(null)

    /** 定位到条目(原 scrollToPositionWithOffset)；onApplied 在定位生效后回调 */
    fun scrollToPosition(index: Int, onApplied: (() -> Unit)? = null) {
        pendingScroll = PendingScroll(index, onApplied)
    }

    private fun viewportMainAxisSize(): Int {
        val li = listState.layoutInfo
        return li.viewportEndOffset - li.viewportStartOffset
    }

    /** 按整屏翻页；animated=false 时同步生效(原 rv.scrollBy) */
    fun scrollPage(direction: Int, animated: Boolean) {
        val delta = viewportMainAxisSize().toFloat() * direction
        if (!animated) {
            listState.dispatchRawDelta(delta)
        } else {
            scope?.launch {
                listState.animateScrollBy(delta, tween(PAGE_ANIM_DURATION, easing = DecelerateEasing))
            }
        }
    }

    // ---- 自动滚动/自动翻页(原 ScrollTimer) ----

    private var autoScrollJob: Job? = null
    private var autoPageJob: Job? = null
    var autoSpeed = 1

    fun setAutoScrollEnabled(enabled: Boolean) {
        autoScrollJob?.cancel()
        autoScrollJob = if (enabled) scope?.launch { autoScrollLoop() } else null
    }

    fun setAutoPageEnabled(enabled: Boolean) {
        autoPageJob?.cancel()
        autoPageJob = if (enabled) scope?.launch {
            while (isActive) {
                delay(autoSpeed.times(1000L))
                onAutoPageTick()
            }
        } else null
    }

    /**
     * 纵向匀速滚动：一段 10000px 匀速段滚完即续下一段(原 IDLE 回调重启)；
     * 被用户手势抢占或滚到底时，等一次“滚动发生→停稳”再续(原 OnScrollListener IDLE 事件驱动)。
     */
    private suspend fun autoScrollLoop() {
        while (coroutineContext.isActive) {
            if (listState.canScrollForward) {
                val time = ceil(16f / autoSpeed.coerceAtLeast(1) * 10000).toInt()
                val finished = try {
                    listState.animateScrollBy(10000f, tween(time, easing = LinearEasing))
                    true
                } catch (e: CancellationException) {
                    coroutineContext.ensureActive()
                    false
                }
                if (finished) continue
            }
            snapshotFlow { listState.isScrollInProgress }.first { it }
            snapshotFlow { listState.isScrollInProgress }.first { !it }
        }
    }

    // ---- Glide 预加载(原 RecyclerViewPreloader + FixedPreloadSizeProvider) ----

    var preloadCount = 0
    private var lastVisibleFirst = -1
    private var lastVisibleLast = -1
    private var preloadedRange = IntRange.EMPTY
    private val preloadTargets = ArrayDeque<Target<*>>()

    internal fun onVisibleRangeChanged(first: Int, last: Int, context: android.content.Context) {
        if (first < 0 || preloadCount <= 0) return
        val forward = first >= lastVisibleFirst
        lastVisibleFirst = first
        lastVisibleLast = last
        val range = if (forward) {
            (last + 1)..(last + preloadCount)
        } else {
            (first - preloadCount) until first
        }
        val snapshot = items
        val book = book ?: return
        for (pos in range) {
            if (pos in preloadedRange) continue
            val item = snapshot.getOrNull(pos) as? MangaPage ?: continue
            val target = ImageLoader.loadManga(context, MangaModel(item.mImageUrl, book, bookSource))
                .preload(context.resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
            preloadTargets.addLast(target)
            // 队列长度对齐 maxPreload：滑出窗口的旧预载请求让 Glide 取消
            while (preloadTargets.size > preloadCount) {
                ImageLoader.with(context).clear(preloadTargets.removeFirst())
            }
        }
        preloadedRange = range
    }

    // ---- GIF 播完翻页装填注册表(原 Activity 遍历 RV children) ----

    private val gifCells = HashMap<Int, () -> MangaPageImageView?>()

    internal fun registerGifCell(index: Int, view: () -> MangaPageImageView?) {
        gifCells[index] = view
    }

    internal fun unregisterGifCell(index: Int, view: () -> MangaPageImageView?) {
        // 列表位移重组时新旧单元格换位注册顺序不定，只移除仍属于自己的登记
        if (gifCells[index] === view) gifCells.remove(index)
    }

    /** 此页是否为“停稳的居中页”(滚动停止且居中)，供播完翻页装填判定 */
    fun isIdleCenterPage(index: Int): Boolean =
        !listState.isScrollInProgress && index == centerItemIndex()

    /** 滚动停稳后调用：居中页 GIF 从第一帧单次播放并准备翻页，其余恢复无限循环 */
    fun syncGifAutoNextForCurrentPage() {
        if (!gifAutoNext) return
        val center = centerItemIndex()
        gifCells.forEach { (index, ref) ->
            val v = ref() ?: return@forEach
            if (index == center && center != -1) {
                v.playGifForCurrentPage()
            } else {
                v.stopGifAutoNext()
            }
        }
    }

    /** 关闭 GIF 自动翻页时调用：所有已附着页恢复无限循环 */
    fun resetAllGifAutoNext() {
        gifCells.forEach { (_, ref) -> ref()?.stopGifAutoNext() }
    }

    /** 帧后执行(原 recyclerView.post)：等本帧组合与测量全部落定再执行 */
    fun post(block: () -> Unit) {
        scope?.launch {
            withFrameNanos { }
            withFrameNanos { }
            block()
        }
    }

    companion object {
        private const val MIN_RATE = 0.5f
        private const val DEFAULT_RATE = 1f
        private const val MAX_RATE = 3f
        private const val DOUBLE_TAP_SCALE = 2f
        private const val ANIM_DURATION = 200
        private const val PAGE_ANIM_DURATION = 400
        private const val SCALE_EPSILON = 0.001f

        // 原 DecelerateInterpolator
        internal val DecelerateEasing = Easing { 1f - (1f - it) * (1f - it) }
    }
}
