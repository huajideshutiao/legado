package io.legado.app.lib.dialogs

data class SelectItem<T>(
    val title: String,
    val value: T
) {

    override fun toString(): String {
        return title
    }

}
