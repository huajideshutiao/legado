package io.legado.app.data.entities.rule

data class RowUi(
    var name: String = "",
    var type: String = "text",
    var action: String? = null,
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

    fun style(): FlexChildStyle {
        return style ?: FlexChildStyle.defaultStyle2
    }

}