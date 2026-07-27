package io.legado.app.data.entities.rule

import kotlinx.serialization.Serializable

@Serializable
data class FlexChildStyle(
    /**
     * 当前行的列数（一行放几项）。
     * 取值 1-4，默认 3；1=整行，2=一行两个，3=一行三个，4=一行四个。
     */
    val cols: Int? = null,

    /**
     * 占据的行数。
     */
    val rows: Int = 1,

    /** 旧版字段，仅做向后兼容：未填 cols 时按数值映射到 cols。 */
    val layout_flexBasisPercent: Float = 0F,
) {

    companion object {
        /** 1/2/3/4 的最小公倍数，作者无需感知底层列数 */
        const val BASE_COLUMN_COUNT = 12
        const val DEFAULT_COLS = 3
        const val MAX_COLS = 4

        val defaultStyle = FlexChildStyle(cols = DEFAULT_COLS)
        val defaultStyle2 = FlexChildStyle(cols = 2)
    }
}
