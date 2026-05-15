package io.legado.app.ui.widget.number

import android.content.Context
import android.view.LayoutInflater
import android.widget.NumberPicker
import io.legado.app.R
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.hideSoftInput


class NumberPickerDialog(private val context: Context) {
    private var title: CharSequence? = null
    private var maxValue: Int? = null
    private var minValue: Int? = null
    private var value: Int? = null
    private var neutralButtonText: Int? = null
    private var neutralButtonListener: (() -> Unit)? = null

    fun setTitle(title: String): NumberPickerDialog {
        this.title = title
        return this
    }

    fun setMaxValue(value: Int): NumberPickerDialog {
        maxValue = value
        return this
    }

    fun setMinValue(value: Int): NumberPickerDialog {
        minValue = value
        return this
    }

    fun setValue(value: Int): NumberPickerDialog {
        this.value = value
        return this
    }

    fun setCustomButton(textId: Int, listener: (() -> Unit)?): NumberPickerDialog {
        neutralButtonText = textId
        neutralButtonListener = listener
        return this
    }

    fun show(callBack: ((value: Int) -> Unit)?) {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_number_picker, null)
        val numberPicker = dialogView.findViewById<NumberPicker>(R.id.number_picker)
        minValue?.let { numberPicker.minValue = it }
        maxValue?.let { numberPicker.maxValue = it }
        value?.let { numberPicker.value = it }

        context.alert(title = title) {
            customView { dialogView }
            positiveButton(R.string.ok) {
                numberPicker.clearFocus()
                numberPicker.hideSoftInput()
                callBack?.invoke(numberPicker.value)
            }
            negativeButton(R.string.cancel)
            neutralButtonText?.let { textId ->
                neutralButton(textId) {
                    numberPicker.clearFocus()
                    numberPicker.hideSoftInput()
                    neutralButtonListener?.invoke()
                }
            }
        }
    }
}