package io.legado.app.data.entities.rule

/**
 * 发现分类
 */
data class ExploreKind(
    val title: String = "",
    /** 一行放几项，1-4；优先级高于旧版 [style]。 */
    val cols: Int? = null,
    val type: String? = RowUi.Type.text,
    val url: String? = null,
    /** 旧属性，仅做向后兼容；新规则请直接用 [cols]。 */
    val style: FlexChildStyle? = null
) {

    fun style(): FlexChildStyle {
        cols?.let { return FlexChildStyle(cols = it) }
        return style ?: FlexChildStyle.defaultStyle
    }

}