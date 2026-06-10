package io.legado.app.ui.book.manga.recyclerview

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.ui.book.read.config.ClickArea
import io.legado.app.utils.findCenterViewPosition
import kotlin.math.abs

class WebtoonFrame : FrameLayout {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    )

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetectorWithLongTap(context, GestureListener())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val clickArea = ClickArea()

    private val recycler: RecyclerView?
        get() = getChildAt(0) as? RecyclerView

    var longTapListener: ((MotionEvent) -> Boolean)? = null

    private var onAction: ((Int) -> Unit)? = null
    fun onAction(callback: (Int) -> Unit) = apply { onAction = callback }

    private var currentScale = DEFAULT_RATE
    private var transX = 0f
    private var transY = 0f

    // 整数化 RV.scrollBy 时残留的小数累积，避免每帧 < 1 像素被 toInt 永远吃掉导致拖不动
    private var scrollRemainderX = 0f
    private var scrollRemainderY = 0f

    // GestureDetector 在 onFling 回调里给的速度（屏幕 px/s），抬手时直接喂给 rv.fling，
    // 让 PagerSnapHelper 用它自己的 minFlingVelocity 阈值判翻不翻——与未缩放时 RV
    // 自己处理 ACTION_UP 的代码路径完全一致。
    private var pendingFlingVelocityX = 0f
    private var pendingFlingVelocityY = 0f

    // 单指事件追踪：未 pan 时存 DOWN 起点用于 touchSlop 比较；pan 中存上一帧位置算 delta
    private var lastX = 0f
    private var lastY = 0f
    private var isPanning = false
    private var animator: AnimatorSet? = null

    // 上一帧 ScaleGestureDetector 的 focus 位置，用来算双指中心的位移
    // ——即"双指拖动"。每次 onScaleBegin 重新捕获。
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    // 画面惯性：用系统 OverScroller，物理曲线和边界停止都跟 RV 自己 fling 时走同一套，
    // 不再单独配速度衰减系数 / 时长。
    private val panScroller = OverScroller(context, DecelerateInterpolator())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clickArea.setRect(w, h)
        // 尺寸变化（旋转）时按新 clamp 重新约束 translation，避免放大态画面飞出屏幕
        if (w != oldw || h != oldh) applyClampedTranslation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
        panScroller.forceFinished(true)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 两个手势探测器在屏幕坐标系下观察完整事件流，不管事件最终被谁消费
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                lastY = ev.y
                isPanning = false
                // 注意：DOWN 不能 cancel 动画。dispatchTouchEvent 已先把事件喂给
                // gestureDetector，双击的第二个 DOWN 会同步触发 onDoubleTap → animateTo()
                // 启动动画；之后 super 调到这里再 cancel 就会把动画当场掐掉，看起来像瞬移。
                // 只有真正开始 pan / 捏合时才取消动画。
            }

            // 第二根手指落下 → 进入捏合，拦截事件交给本层处理
            MotionEvent.ACTION_POINTER_DOWN -> return true

            MotionEvent.ACTION_MOVE -> {
                // 已放大且单指拖动距离超过 touchSlop，拦截事件做 pan
                if (currentScale > DEFAULT_RATE &&
                    (abs(ev.x - lastX) > touchSlop || abs(ev.y - lastY) > touchSlop)
                ) {
                    beginPan(ev.x, ev.y)
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // 捏合进行中不做单指 pan，避免两套位移叠加
                if (!scaleDetector.isInProgress && ev.pointerCount == 1 && isPanning) {
                    val dx = ev.x - lastX
                    val dy = ev.y - lastY
                    lastX = ev.x
                    lastY = ev.y
                    panBy(dx, dy)
                }
            }

            // 捏合中抬起一根手指：把 pan 基准切换到留在屏幕上的那根，避免位移跳变
            MotionEvent.ACTION_POINTER_UP -> {
                val lifted = ev.actionIndex
                val remaining = if (lifted == 0) 1 else 0
                if (remaining < ev.pointerCount) {
                    lastX = ev.getX(remaining)
                    lastY = ev.getY(remaining)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isPanning) {
                    isPanning = false
                    finishPan()
                }
            }
        }
        return true
    }

    private fun beginPan(x: Float, y: Float) {
        lastX = x
        lastY = y
        isPanning = true
        animator?.cancel()
        panScroller.forceFinished(true) // 接管惯性，避免画面被两路写入打架
        // 清零小数累计，让每次手势独立结算
        scrollRemainderX = 0f
        scrollRemainderY = 0f
    }

    private fun clampMaxX(): Float =
        ((width / 2f) * (currentScale - 1f)).coerceAtLeast(0f)

    private fun clampMaxY(): Float =
        ((height / 2f) * (currentScale - 1f)).coerceAtLeast(0f)

    private fun applyClampedTranslation() {
        // 标准图库钳制：图片边不越过屏幕边，画面始终被图填满。
        // scale ≤ 1 时上限为 0，强制居中（橡皮筋过程中也不让 pan）。
        transX = transX.coerceIn(-clampMaxX(), clampMaxX())
        transY = transY.coerceIn(-clampMaxY(), clampMaxY())
        recycler?.let {
            it.translationX = transX
            it.translationY = transY
        }
    }

    fun resetZoom() {
        animateTo(DEFAULT_RATE, 0f, 0f)
    }

    private fun floatAnim(from: Float, to: Float, set: (Float) -> Unit): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            addUpdateListener { set(it.animatedValue as Float) }
        }

    private fun runAnim(durationMs: Long, vararg anims: ValueAnimator) {
        animator?.cancel()
        animator = AnimatorSet().apply {
            playTogether(*anims)
            duration = durationMs
            interpolator = DecelerateInterpolator()
            // 不在 doOnEnd 里 snap 到目标——updateListener 每帧已维护当前值，
            // 正常结束时最后一帧就是目标；cancel 时反而绝不能 snap，否则
            // 用户中途上手 pan 会先看到一下"跳到终点"再跟手，明显闪现。
            start()
        }
    }

    private fun animateTo(toScale: Float, toX: Float, toY: Float) {
        runAnim(
            ANIMATOR_DURATION_TIME,
            floatAnim(currentScale, toScale) {
                currentScale = it
                recycler?.scaleX = it
                recycler?.scaleY = it
            },
            floatAnim(transX, toX) {
                transX = it
                recycler?.translationX = it
            },
            floatAnim(transY, toY) {
                transY = it
                recycler?.translationY = it
            },
        )
    }

    /**
     * 单指 pan：finger delta 先吃进 transX/Y 做钳制，钳掉的余量按 1:1 直接喂给 RV.scrollBy ——
     * 图片视觉停在 clamp 边沿，RV 在底下连续滚动，下一页/下一段内容跟手滑入，
     * 与未缩放时拖动手感完全一致（"无限画布"）。
     * 抬手是否翻页由 rv.fling(velocityX, velocityY) 让 PagerSnapHelper 自己用速度判，
     * 这里不再累计距离。
     */
    private fun panBy(dx: Float, dy: Float) {
        val rawX = transX + dx
        val rawY = transY + dy
        transX = rawX
        transY = rawY
        applyClampedTranslation() // 钳制写回 transX/Y 并同步 recycler.translation
        val residualX = rawX - transX
        val residualY = rawY - transY
        if (residualX == 0f && residualY == 0f) return

        val rv = recycler ?: return
        // 手指右拖（residual > 0）想看左侧内容（上一页），所以 scrollBy 取反向。
        // 关键：RV.scrollBy 用的是 RV 内部布局坐标（pre-scale），而 RV 被整体放大了
        // currentScale 倍，所以滚 N 像素在屏幕上看到的是 N * scale 像素的滑动。
        // 除以 currentScale 抵消这层放大，让"手指 1px ↔ 页面屏幕 1px"严格 1:1，
        // 同时让基于屏幕宽度的翻页阈值与肉眼可见的滑动距离一致。
        // 小数累积到下一帧，避免 toInt 把每帧的 < 1 像素永远吃掉导致拖不动。
        scrollRemainderX += -residualX / currentScale
        scrollRemainderY += -residualY / currentScale
        val intDx = scrollRemainderX.toInt()
        val intDy = scrollRemainderY.toInt()
        if (intDx != 0 || intDy != 0) {
            rv.scrollBy(intDx, intDy)
            scrollRemainderX -= intDx
            scrollRemainderY -= intDy
        }
    }

    /**
     * 抬手 / 取消时判定：把 GestureDetector 给的屏幕 px/s 反号送进 rv.fling，
     * PagerSnapHelper.onFling 用自己的 minFlingVelocity 决定翻不翻——和未缩放时
     * RV 自己处理 ACTION_UP 的代码路径完全一致。fling 没触发（速度太小）就回退
     * 到 smoothScrollToPosition(findCenterViewPosition())，PagerSnapHelper 把被
     * scrollBy 偏离的位置归位到 center 页。翻页后 transX 保持不变，
     * 上一页停在哪个角落，下一页也从同样的角落开始（applyClampedTranslation 保证不越界）。
     */
    private fun finishPan() {
        val rv = recycler ?: return
        val vx = pendingFlingVelocityX
        val vy = pendingFlingVelocityY
        pendingFlingVelocityX = 0f
        pendingFlingVelocityY = 0f

        // 画面惯性：用 OverScroller 在 [-clampMax, +clampMax] 内 fling，撞边自然停。
        // 横向/纵向同样处理，不区分翻页方向。
        flingPan(vx, vy)

        // RV 自身的 fling：与未缩放时手指直接给 RV 的量纲一致；横向由 PagerSnapHelper
        // 决定翻不翻，纵向就是普通滚动。反号是因为手指方向与 RV scroll 方向相反。
        val flung = rv.fling(-vx.toInt(), -vy.toInt())
        if (!flung) {
            val center = rv.findCenterViewPosition()
            if (center != RecyclerView.NO_POSITION) {
                rv.smoothScrollToPosition(center)
            }
        }
    }

    private fun flingPan(vx: Float, vy: Float) {
        // transX 与手指方向同向（手指右滑 vx>0，transX 增大），scroller 直接复用
        // transX/Y 当坐标、vx/vy 当速度，边界 [-clampMax, +clampMax] 让 OverScroller 自然停。
        val maxX = clampMaxX().toInt()
        val maxY = clampMaxY().toInt()
        panScroller.fling(
            transX.toInt(), transY.toInt(),
            vx.toInt(), vy.toInt(),
            -maxX, maxX, -maxY, maxY,
        )
        if (!panScroller.isFinished) postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        super.computeScroll()
        if (panScroller.computeScrollOffset()) {
            transX = panScroller.currX.toFloat()
            transY = panScroller.currY.toFloat()
            recycler?.let {
                it.translationX = transX
                it.translationY = transY
            }
            postInvalidateOnAnimation()
        }
    }

    // 浮点容差：弹回 / 动画末尾可能留下极小残差，要按"1×"看待
    private fun isAtDefaultScale(): Boolean = abs(currentScale - DEFAULT_RATE) < SCALE_EPSILON

    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // 单指 pan 进行中（即便没露出相邻页）也不允许中途加指切到缩放，
            // 必须手指全抬后重新两指落下才能进缩放。
            if (isPanning) return false
            animator?.cancel()
            panScroller.forceFinished(true) // 同 onDoubleTap：抢占翻页惯性的写入权
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val nextScale =
                (currentScale * detector.scaleFactor).coerceIn(MIN_RATE, MAX_RATE)
            val ratio = nextScale / currentScale
            val hw = width / 2f
            val hh = height / 2f
            // 锚 lastFocus 那一点经缩放后落到当前 focus 下：
            // - lastFocus == focus 时退化为纯焦点锚定缩放
            // - ratio == 1 时退化为纯双指拖动 (transX += focus - lastFocus)
            transX = detector.focusX - hw - (lastFocusX - hw - transX) * ratio
            transY = detector.focusY - hh - (lastFocusY - hh - transY) * ratio
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            currentScale = nextScale
            recycler?.let {
                it.scaleX = currentScale
                it.scaleY = currentScale
            }
            applyClampedTranslation()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            // 缩小到 1× 以下时弹回 1×，实现橡皮筋手感
            if (currentScale < DEFAULT_RATE) resetZoom()
        }
    }

    inner class GestureListener : GestureDetectorWithLongTap.Listener() {

        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            // 用屏幕坐标判定九宫格，这样点击区域始终跟着屏幕走，与缩放/平移无关
            val action = clickArea.getAction(ev.x, ev.y)
            if (action != -1) onAction?.invoke(action)
            return true
        }

        override fun onDoubleTap(ev: MotionEvent): Boolean {
            animator?.cancel()
            panScroller.forceFinished(true) // 翻页惯性窗口内双击会被惯性反向覆写 transX，必须先停
            // 只要不是恰好 1×（无论放大还是缩小中的中间态），都先归位到 1×
            if (!isAtDefaultScale()) {
                animateTo(DEFAULT_RATE, 0f, 0f)
            } else {
                val toScale = DOUBLE_TAP_SCALE
                val hw = width / 2f
                val hh = height / 2f
                // 锚定双击位置：放大后该屏幕点继续位于该屏幕点之下
                val toX = (hw - ev.x) * (toScale - 1f)
                val toY = (hh - ev.y) * (toScale - 1f)
                animateTo(toScale, toX, toY)
            }
            return true
        }

        override fun onLongTapConfirmed(ev: MotionEvent): Boolean {
            val handled = longTapListener?.invoke(ev) == true
            if (handled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return handled
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            // 缩放态下的 fling 由 finishPan 在 ACTION_UP 时一并喂给 rv.fling，避免双路径
            if (isPanning) {
                pendingFlingVelocityX = velocityX
                pendingFlingVelocityY = velocityY
                return true
            }
            // 未放大时让 RecyclerView 自己处理 fling，做正常滚动
            return false
        }
    }
}

private const val MIN_RATE = 0.5f
private const val DEFAULT_RATE = 1f
private const val MAX_RATE = 3f
private const val DOUBLE_TAP_SCALE = 2f
private const val ANIMATOR_DURATION_TIME = 200L
private const val SCALE_EPSILON = 0.001f
