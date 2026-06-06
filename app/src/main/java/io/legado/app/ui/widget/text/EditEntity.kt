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

    object ViewType {

        const val text = 0
        const val checkBox = 1
        const val spinner = 2

    }

}
