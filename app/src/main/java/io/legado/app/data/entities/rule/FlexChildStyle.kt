package io.legado.app.data.entities.rule

import android.view.View
import android.widget.GridLayout

data class FlexChildStyle(
    /**
     * 当前行的列数（一行放几项）。
     * 取值 1-4，默认 3；1=整行，2=一行两个，3=一行三个，4=一行四个。
     */
    val cols: Int? = null,

    /** 旧版字段，仅做向后兼容：未填 cols 时按数值映射到 cols。 */
    val layout_flexBasisPercent: Float = 0F,
) {

    private fun resolveCols(): Int {
        cols?.let { return it.coerceIn(1, MAX_COLS) }
        if (layout_flexBasisPercent <= 0f) return DEFAULT_COLS
        // 计算在 100% 宽度内最多能放多少个，使用 1.001f 容错以处理 1/3 等浮点数精度问题
        return (1.001f / layout_flexBasisPercent).toInt().coerceIn(1, MAX_COLS)
    }

    fun apply(view: View) {
        val lp = view.layoutParams as? GridLayout.LayoutParams ?: return
        lp.width = 0
        lp.columnSpec =
            GridLayout.spec(GridLayout.UNDEFINED, BASE_COLUMN_COUNT / resolveCols(), 1F)
    }

    companion object {
        /** 1/2/3/4 的最小公倍数，作者无需感知底层列数 */
        const val BASE_COLUMN_COUNT = 12
        const val DEFAULT_COLS = 3
        const val MAX_COLS = 4

        val defaultStyle = FlexChildStyle(cols = DEFAULT_COLS)
        val defaultStyle2 = FlexChildStyle(cols = 2)
    }
}
