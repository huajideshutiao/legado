package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout

/**
 * 支持 maxHeight 约束的 LinearLayout，用于对话框高度自适应场景。
 *
 * 在 AT_MOST 模式（Window WRAP_CONTENT）下：
 * - 临时把 0dp+weight 子 View 改为 WRAP_CONTENT，测量真实内容高度
 * - 内容 < maxHeight：自动收缩到内容高度
 * - 内容 >= maxHeight：用 EXACTLY + maxHeight 重新测量，0dp+weight 子 View 填满并滚动
 *
 * 在 EXACTLY 模式（isFullHeight=true，Window 固定高度）下不干预，行为同普通 LinearLayout。
 *
 * layout 阶段正确性：onMeasure 中临时修改 lp.height，测量后恢复为 0。
 * LinearLayout.onLayout 根据自身 height 计算剩余空间分配给 weight 子 View，
 * 剩余空间恰好等于 0dp+weight 子 View 的内容高度，layout 结果与 measure 一致。
 */
class AutoShrinkLinearLayout : LinearLayout {

    var maxHeight: Int = Int.MAX_VALUE

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        if (mode == MeasureSpec.AT_MOST || mode == MeasureSpec.UNSPECIFIED) {
            // 临时把 0dp+weight 子 View 改为 WRAP_CONTENT，让 LinearLayout 测出真实内容高度
            val swaps = swapZeroDpWeightToWrapContent()
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            )
            restoreSwaps(swaps)
            // 内容超过 maxHeight 时，用 EXACTLY 重新测量（0dp+weight 恢复，子 View 填满并滚动）
            if (measuredHeight >= maxHeight) {
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.EXACTLY)
                )
            }
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun swapZeroDpWeightToWrapContent(): List<Pair<View, Int>> {
        val swaps = mutableListOf<Pair<View, Int>>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LayoutParams ?: continue
            if (lp.height == 0 && lp.weight > 0f) {
                swaps.add(child to 0)
                lp.height = WRAP_CONTENT
            }
        }
        return swaps
    }

    private fun restoreSwaps(swaps: List<Pair<View, Int>>) {
        swaps.forEach { (child, height) ->
            child.layoutParams.height = height
        }
    }
}
