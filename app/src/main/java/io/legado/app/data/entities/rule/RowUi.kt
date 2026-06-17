package io.legado.app.data.entities.rule

data class RowUi(
    var name: String = "",
    /** 一行放几项，1-4；优先级高于旧版 [style]。 */
    var cols: Int? = null,
    var type: String = "text",
    var action: String? = null,
    /** 旧属性，仅做向后兼容；新规则请直接用 [cols]。 */
    var style: FlexChildStyle? = null,
    var chars: List<String>? = null
) {

    @Suppress("ConstPropertyName")
    object Type {

        const val text = "text"
        const val password = "password"
        const val button = "button"
        const val toggle = "toggle"
        const val title = "title"
        const val select = "select"

    }

    fun style(default: FlexChildStyle = FlexChildStyle.defaultStyle2): FlexChildStyle {
        cols?.let { return FlexChildStyle(cols = it) }
        return style ?: default
    }

}