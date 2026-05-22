package io.legado.app.ui.widget.number

import android.content.Context
import android.view.LayoutInflater
import android.widget.NumberPicker
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.lib.dialogs.cancelButton
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.neutralButton
import io.legado.app.lib.dialogs.okButton
import io.legado.app.lib.dialogs.showWithTint
import io.legado.app.utils.hideSoftInput

fun showNumberPicker(
    context: Context,
    @StringRes titleResId: Int? = null,
    title: CharSequence? = null,
    max: Int? = null,
    min: Int? = null,
    value: Int? = null,
    neutralButton: Pair<Int, (() -> Unit)?>? = null,
    onConfirm: ((value: Int) -> Unit)? = null
) {
    val dialogView = LayoutInflater.from(context)
        .inflate(R.layout.dialog_number_picker, null)
    val numberPicker = dialogView.findViewById<NumberPicker>(R.id.number_picker)
    min?.let { numberPicker.minValue = it }
    max?.let { numberPicker.maxValue = it }
    value?.let { numberPicker.value = it }

    AlertDialog.Builder(context).apply {
        title?.let { setTitle(it) }
        titleResId?.let { setTitle(it) }
        customView { dialogView }
        okButton {
            numberPicker.clearFocus()
            numberPicker.hideSoftInput()
            onConfirm?.invoke(numberPicker.value)
        }
        cancelButton()
        neutralButton?.let { (textId, listener) ->
            neutralButton(textId) {
                numberPicker.clearFocus()
                numberPicker.hideSoftInput()
                listener?.invoke()
            }
        }
    }.showWithTint()
}
