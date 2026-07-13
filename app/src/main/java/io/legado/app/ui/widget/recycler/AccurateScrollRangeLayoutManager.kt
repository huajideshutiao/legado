package io.legado.app.ui.widget.recycler

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 重写滚动范围计算，使用实际渲染高度而非估算值，
 * 解决快速滚动条在 item 高度不一致时跳变的问题。
 */
class AccurateScrollRangeLayoutManager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayoutManager(context, attrs, defStyleAttr, defStyleRes) {

    override fun computeVerticalScrollRange(state: RecyclerView.State): Int {
        var range = 0
        val itemCount = itemCount
        if (itemCount == 0) return 0

        // 遍历所有 item 计算实际高度总和
        for (i in 0 until itemCount) {
            val view = findViewByPosition(i)
            if (view != null) {
                // 已渲染的 item 使用实际高度
                range += view.measuredHeight
            } else {
                // 未渲染的 item 使用默认估算值（100px）
                range += 100
            }
        }
        return range
    }

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int {
        return height
    }
}
