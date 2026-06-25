package io.legado.app.lib.prefs

import android.app.Dialog
import android.os.Bundle
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.applyEInkDialogStyle
import io.legado.app.utils.applyPreferenceDialogStyle

class EditTextPreferenceDialog : EditTextPreferenceDialogFragmentCompat() {

    companion object {

        fun newInstance(key: String): EditTextPreferenceDialog {
            val fragment = EditTextPreferenceDialog()
            val b = Bundle(1)
            b.putString(ARG_KEY, key)
            fragment.arguments = b
            return fragment
        }

    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.applyPreferenceDialogStyle(requireContext())
        return dialog
    }

    override fun onStart() {
        super.onStart()
        if (AppConfig.isEInkMode) {
            dialog?.window?.applyEInkDialogStyle()
        }
    }

}
