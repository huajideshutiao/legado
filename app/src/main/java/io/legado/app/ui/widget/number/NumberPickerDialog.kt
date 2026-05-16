package io.legado.app.ui.widget.number

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.NumberPicker
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.lib.dialogs.AndroidAlertBuilder
import io.legado.app.utils.applyTint
import io.legado.app.utils.hideSoftInput

class NumberPickerDialog : BaseDialogFragment(0) {

    private var titleText: CharSequence? = null
    private var titleResId: Int? = null
    private var maxValue: Int? = null
    private var minValue: Int? = null
    private var value: Int? = null
    private var neutralButtonText: Int? = null
    private var neutralButtonListener: (() -> Unit)? = null
    private var callback: ((value: Int) -> Unit)? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {}

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_number_picker, null)
        val numberPicker = dialogView.findViewById<NumberPicker>(R.id.number_picker)
        minValue?.let { numberPicker.minValue = it }
        maxValue?.let { numberPicker.maxValue = it }
        value?.let { numberPicker.value = it }

        return AndroidAlertBuilder(requireContext()).apply {
            titleText?.let { setTitle(it) }
            titleResId?.let { setTitle(it) }
            setCustomView(dialogView)
            okButton {
                numberPicker.clearFocus()
                numberPicker.hideSoftInput()
                callback?.invoke(numberPicker.value)
            }
            cancelButton()
            neutralButtonText?.let { textId ->
                neutralButton(textId) {
                    numberPicker.clearFocus()
                    numberPicker.hideSoftInput()
                    neutralButtonListener?.invoke()
                }
            }
        }.build()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? androidx.appcompat.app.AlertDialog)?.applyTint()
    }

    fun setTitle(title: String): NumberPickerDialog {
        titleText = title
        titleResId = null
        return this
    }

    fun setTitleRes(titleResId: Int): NumberPickerDialog {
        this.titleResId = titleResId
        titleText = null
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
        this.callback = callBack
    }

    companion object {
        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            block: NumberPickerDialog.() -> Unit
        ) {
            NumberPickerDialog().apply {
                block()
                show(fragmentManager, "numberPickerDialog")
            }
        }
    }
}
