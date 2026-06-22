package io.legado.app.ui.main.home

import android.content.Context
import android.util.DisplayMetrics
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * 横向起始对齐吸附：滑动停止时把最靠近起始边的列吸附到起点，露出下一列，形成翻列的限位感。
 */
class StartSnapHelper : LinearSnapHelper() {

    companion object {
        /** 每英寸滚动耗时（ms）：默认 25f，调大让翻列减速更慢、阻尼更重 */
        private const val MILLISECONDS_PER_INCH_STICKY = 90f
    }

    private var helper: OrientationHelper? = null
    private var helperLm: RecyclerView.LayoutManager? = null
    private var snapContext: Context? = null

    override fun attachToRecyclerView(recyclerView: RecyclerView?) {
        super.attachToRecyclerView(recyclerView)
        snapContext = recyclerView?.context
    }

    /**
     * 加大吸附阻尼：把翻列动画的减速曲线放慢（默认 25ms/inch 偏滑），手感更粘稠。
     */
    override fun createScroller(
        layoutManager: RecyclerView.LayoutManager
    ): RecyclerView.SmoothScroller? {
        if (layoutManager !is RecyclerView.SmoothScroller.ScrollVectorProvider) return null
        val context = snapContext ?: return super.createScroller(layoutManager)
        return object : LinearSmoothScroller(context) {
            override fun onTargetFound(
                targetView: View,
                state: RecyclerView.State,
                action: Action
            ) {
                val dist = calculateDistanceToFinalSnap(layoutManager, targetView)
                val dx = dist[0]
                val dy = dist[1]
                val time = calculateTimeForDeceleration(maxOf(abs(dx), abs(dy)))
                if (time > 0) {
                    action.update(dx, dy, time, DecelerateInterpolator())
                }
            }

            // 值越大每像素耗时越长 → 翻列越慢越黏（默认 25f）
            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float =
                MILLISECONDS_PER_INCH_STICKY / displayMetrics.densityDpi
        }
    }

    override fun calculateDistanceToFinalSnap(
        layoutManager: RecyclerView.LayoutManager,
        targetView: View
    ): IntArray {
        val out = IntArray(2)
        out[0] = if (layoutManager.canScrollHorizontally()) {
            val h = orientationHelper(layoutManager)
            h.getDecoratedStart(targetView) - h.startAfterPadding
        } else 0
        return out
    }

    /**
     * 限位：无论 fling 速度多大，落点最多与当前列相差一列，避免一甩越过多列。
     */
    override fun findTargetSnapPosition(
        layoutManager: RecyclerView.LayoutManager,
        velocityX: Int,
        velocityY: Int
    ): Int {
        if (layoutManager !is LinearLayoutManager) return RecyclerView.NO_POSITION
        if (!layoutManager.canScrollHorizontally()) return RecyclerView.NO_POSITION
        val firstPos = layoutManager.findFirstVisibleItemPosition()
        if (firstPos == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION
        val firstView = layoutManager.findViewByPosition(firstPos)
            ?: return RecyclerView.NO_POSITION
        val h = orientationHelper(layoutManager)
        val itemWidth = h.getDecoratedMeasurement(firstView)
        // 第一列被起始边裁掉的宽度；接近 0 表示当前已对齐到 firstPos
        val clipped = h.startAfterPadding - h.getDecoratedStart(firstView)
        val aligned = clipped <= itemWidth / 10
        val target = when {
            velocityX > 0 -> firstPos + 1
            velocityX < 0 -> if (aligned) firstPos - 1 else firstPos
            else -> return RecyclerView.NO_POSITION
        }
        return target.coerceIn(0, layoutManager.itemCount - 1)
    }

    override fun findSnapView(layoutManager: RecyclerView.LayoutManager): View? {
        if (layoutManager !is LinearLayoutManager) return super.findSnapView(layoutManager)
        val firstPos = layoutManager.findFirstVisibleItemPosition()
        if (firstPos == RecyclerView.NO_POSITION) return null
        // 已到末尾就吸附最后一列，避免无法对齐
        if (layoutManager.findLastCompletelyVisibleItemPosition() == layoutManager.itemCount - 1) {
            return layoutManager.findViewByPosition(layoutManager.itemCount - 1)
        }
        val firstView = layoutManager.findViewByPosition(firstPos) ?: return null
        val h = orientationHelper(layoutManager)
        val visible = h.getDecoratedEnd(firstView) - h.startAfterPadding
        val itemWidth = h.getDecoratedMeasurement(firstView)
        // 第一列露出不足一半 → 吸附到下一列
        return if (visible >= itemWidth / 2) firstView
        else layoutManager.findViewByPosition(firstPos + 1) ?: firstView
    }

    private fun orientationHelper(lm: RecyclerView.LayoutManager): OrientationHelper {
        if (helper == null || helperLm !== lm) {
            helper = OrientationHelper.createHorizontalHelper(lm)
            helperLm = lm
        }
        return helper!!
    }
}
