package io.legado.app.ui.widget.text

import splitties.init.appCtx

data class EditEntity(
    var key: String,
    var value: String?,
    var hint: String,
    val viewType: Int = ViewType.text,
    val selections: List<Pair<String, String?>>? = null,
    val span: Int = 2
) {

    constructor(
        key: String,
        value: String?,
        hint: Int,
        viewType: Int = ViewType.text,
        selections: List<Pair<String, String?>>? = null,
        span: Int = 2
    ) : this(
        key,
        value,
        appCtx.getString(hint),
        viewType,
        selections,
        span
    )

    /** 文本字段值：trim 后空串视为 null */
    val text: String? get() = value?.takeIf { it.isNotBlank() }

    /** 复选框值：value == "true" */
    val boolValue: Boolean get() = value == "true"

    /** 下拉框选中下标：value.toIntOrNull() ?: 0 */
    val intValue: Int get() = value?.toIntOrNull() ?: 0

    object ViewType {

        const val text = 0
        const val checkBox = 1
        const val spinner = 2

    }

}
