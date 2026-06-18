package io.legado.app.data.entities.rule

/**
 * 发现分类
 */
data class ExploreKind(
    val title: String = "",
    val type: String? = RowUi.Type.text,
    val url: String? = null,
    val style: FlexChildStyle? = null
) {

    fun style(): FlexChildStyle {
        return style ?: FlexChildStyle.defaultStyle
    }

}