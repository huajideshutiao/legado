package io.legado.app.ui.widget.number

import android.content.Context
import android.widget.NumberPicker
import androidx.annotation.StringRes
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.ui.compose.dialogs.alert
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
    lateinit var numberPicker: NumberPicker
    context.alert {
        title?.let { setTitle(it) }
        titleResId?.let { setTitle(it) }
        customView {
            AndroidView(factory = { ctx ->
                NumberPicker(ctx).apply {
                    isVerticalScrollBarEnabled = false
                    min?.let { minValue = it }
                    max?.let { maxValue = it }
                    value?.let { this.value = it }
                    numberPicker = this
                }
            })
        }
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
    }
}
