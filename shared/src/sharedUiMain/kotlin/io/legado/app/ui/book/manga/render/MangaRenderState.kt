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
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.lerp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
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
 * 预加载(原 RecyclerViewPreloader)/GIF 装填注册表。
 * scale/trans 只被 graphicsLayer 块读取，缩放平移不触发重组与列表重测量。
 *
 * 平台注入点（app/desktop 各自提供）：
 * - [clickActionAt]：点击九宫格动作判定（app 用 ClickArea+AppConfig，desktop 默认无点击动作）。
 * - [onContainerSizeExtra]：容器尺寸变化时的额外回调（app 用来重设 ClickArea 矩形）。
 * - [preloadExecutor]：图片预加载执行体（Android/iOS/desktop 经 Coil3 WRITE_ONLY 预载到内存缓存;
 *   鸿蒙无 coil3 变体, 预载回填共享磁盘缓存）。
 * - [MangaPageRenderer]：GIF 单元格渲染器接口（app 用 MangaPageImageView，desktop 无 GIF）。
 */
class MangaRenderState {

    val listState = LazyListState()

    var items by mutableStateOf<List<BaseMangaPage>>(emptyList())
    var horizontal by mutableStateOf(false)
    var colorFilterConfig by mutableStateOf(MangaColorFilterConfig())
    var grayEnabled by mutableStateOf(false)
    var gifAutoNext = false

    var book: Book? = null
    var bookSource: BookSource? = null

    // ---- 由调用方注入的回调 ----
    var onAction: (Int) -> Unit = {}
    var onLongTap: () -> Boolean = { false }
    /**
     * 居中页变化。第二参 = 本次上报是否来自 items 重建后的"按 key 重锚"而非真实滚动
     * (见 [MangaRenderLayer] 内的基线判定): 重锚不代表用户滚动, 不得据此跨章,
     * 但它携带的页码是有效的, 调用方应照常同步章内页码。
     */
    var onCenterItemChanged: (Int, Boolean) -> Unit = { _, _ -> }
    var onScrollIdle: () -> Unit = {}
    var onAutoPageTick: () -> Unit = {}
    var onGifTurnPage: () -> Boolean = { false }

    // ---- 平台注入点 ----
    /** 点击九宫格动作判定（app 用 ClickArea，desktop 默认返回 -1 无动作） */
    var clickActionAt: (Float, Float) -> Int = { _, _ -> -1 }
    /** 容器尺寸变化的额外回调（app 用来重设 ClickArea 矩形） */
    var onContainerSizeExtra: ((IntSize) -> Unit)? = null
    /** 图片预加载执行体（app 用 Coil3 WRITE_ONLY 写内存缓存，desktop 不预加载） */
    var preloadExecutor: (suspend (url: String, book: Book, source: BookSource?) -> Unit)? = null

    // ---- 组合期注入 ----
    var scope: CoroutineScope? = null
    var flingBehavior: FlingBehavior? = null
    var decaySpec: DecayAnimationSpec<Float>? = null

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
        onContainerSizeExtra?.invoke(size)
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
     * 鼠标拖拽松手后的惯性/吸附 (由 [mangaMouseDragGestures] 调用):
     * 横向走单页 snap (与触摸 finishPan 同一条 fling 路径, 保证拖到半页松手也能归位), 纵向走普通衰减。
     */
    internal fun flingAfterMouseDrag(velocity: Float) {
        val fb = flingBehavior ?: return
        val v = velocity
        scope?.launch {
            listState.scroll { with(fb) { performFling(-v) } }
        }
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

    fun onSingleTap(pos: Offset) {
        // 用屏幕坐标判定九宫格，点击区域始终跟着屏幕走，与缩放/平移无关
        val action = clickActionAt(pos.x, pos.y)
        if (action != -1) onAction(action)
    }

    fun onDoubleTap(pos: Offset) {
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
    class PendingScroll(
        val index: Int,
        /** 条目顶部相对视口顶的偏移 (对照 LazyListState.scrollToItem 的 scrollOffset) */
        val scrollOffset: Int = 0,
        val onApplied: (() -> Unit)? = null,
    )

    var pendingScroll by mutableStateOf<PendingScroll?>(null)

    /**
     * 等待跳转定位中 (初次打开/菜单切章/目录选章/重载)。
     *
     * 只表达"这轮内容就绪后需要定位到 contentPos", 由内容层置位、[scrollToPosition] 的请求
     * 生效后由渲染层清除。**不要**用它门控居中页上报: 它在 LaunchedEffect 里置位, 而桌面端
     * 同一帧 layout 先于 effect 执行, 上报会早于置位 —— 上报的抑制改由 [MangaRenderLayer]
     * 内的 "items 引用变化那次只刷基线" 完成。
     */
    var awaitingJump by mutableStateOf(true)

    /**
     * 定位到条目(原 scrollToPositionWithOffset)；onApplied 在定位生效后回调。
     * [scrollOffset] 为条目顶部相对视口顶的偏移，用于 items 重建后按原视口位置恢复。
     */
    fun scrollToPosition(
        index: Int,
        scrollOffset: Int = 0,
        onApplied: (() -> Unit)? = null,
    ) {
        pendingScroll = PendingScroll(index, scrollOffset, onApplied)
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
                listState.animateScrollBy(
                    delta,
                    tween(PAGE_ANIM_DURATION, easing = DecelerateEasing)
                )
            }
        }
    }

    /** 按视口比例滚动 (方向键小步滚动用, 用户拍板: 纵向模式一次整页难受); fraction 0<f<1 */
    fun scrollPagePart(direction: Int, fraction: Float, animated: Boolean) {
        val delta = viewportMainAxisSize().toFloat() * fraction * direction
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

    // ---- 预加载(原 RecyclerViewPreloader + FixedPreloadSizeProvider) ----
    // 平台无关的区间簿记 + 调用 [preloadExecutor] 执行实际图片预加载(Android/iOS/desktop 用 Coil3,
    // 鸿蒙回填磁盘缓存)

    var preloadCount = 0
    private var lastVisibleFirst = -1
    private var lastVisibleLast = -1
    private var preloadedRange = IntRange.EMPTY
    private val preloadTargets = ArrayDeque<Job>()

    fun onVisibleRangeChanged(first: Int, last: Int) {
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
        val exec = preloadExecutor ?: return
        for (pos in range) {
            if (pos in preloadedRange) continue
            val item = snapshot.getOrNull(pos) as? MangaPage ?: continue
            val job = scope?.launch { exec(item.mImageUrl, book, bookSource) } ?: return
            preloadTargets.addLast(job)
            // 队列长度对齐 maxPreload: 滑出窗口的旧预载请求取消
            while (preloadTargets.size > preloadCount) {
                preloadTargets.removeFirst().cancel()
            }
        }
        preloadedRange = range
    }

    // ---- GIF 播完翻页装填注册表(原 Activity 遍历 RV children) ----

    /**
     * GIF 单元格渲染器接口（平台注入）：
     * - app actual: MangaPageImageView（Coil3 + MovieDrawable/AnimatedImageDrawable）
     * - desktop: 无 GIF 动图支持，注册表保持空，方法 no-op
     */
    interface MangaPageRenderer {
        fun playGifForCurrentPage()
        fun stopGifAutoNext()
    }

    private val gifCells = HashMap<Int, () -> MangaPageRenderer?>()

    fun registerGifCell(index: Int, view: () -> MangaPageRenderer?) {
        gifCells[index] = view
    }

    fun unregisterGifCell(index: Int, view: () -> MangaPageRenderer?) {
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

/**
 * 列表条目稳定 key，与 [MangaRenderLayer] 的 items(key=) 一致。
 *
 * 供 items 重建后按 key 锚定"重建前居中条目"，消除像素偏移在列表头尾结构变化时的错位。
 */
internal fun BaseMangaPage.listKey(): String = when (this) {
    is MangaPage -> "p:${chapterIndex}:${index}"
    else -> "l:${chapterIndex}"
}

/**
 * 横向漫画的单页吸附行为。
 *
 * Compose 默认 LazyList SnapFlingBehavior 会先按速度衰减一段距离，再吸附到目标项；快速甩动时
 * 可能越过多项。原 Android RecyclerView 使用 PagerSnapHelper，一次 fling 只选择相邻页。
 * 这里保留 LazyList 原有的中心吸附计算，但禁用 approach 阶段，使单次手势只在手势结束时
 * 可见的当前页和相邻页之间做最终吸附，不再继续惯性穿越多页。
 */
@Composable
fun rememberSinglePageSnapFlingBehavior(state: LazyListState): FlingBehavior {
    val delegate = remember(state) { SnapLayoutInfoProvider(state) }
    val singlePageProvider = remember(delegate) {
        object : SnapLayoutInfoProvider {
            override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f

            override fun calculateSnapOffset(velocity: Float): Float =
                delegate.calculateSnapOffset(velocity)
        }
    }
    return rememberSnapFlingBehavior(singlePageProvider)
}
