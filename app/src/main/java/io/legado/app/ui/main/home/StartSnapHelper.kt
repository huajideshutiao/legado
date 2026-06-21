package io.legado.app.ui.main.home

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * 横向起始对齐吸附：滑动停止时把最靠近起始边的列吸附到起点，露出下一列，形成翻列的限位感。
 */
class StartSnapHelper : LinearSnapHelper() {

    private var helper: OrientationHelper? = null
    private var helperLm: RecyclerView.LayoutManager? = null

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
