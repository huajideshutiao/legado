package io.legado.app.lib.prefs

import android.app.Dialog
import android.os.Bundle
import androidx.preference.MultiSelectListPreferenceDialogFragmentCompat
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.applyEInkDialogStyle
import io.legado.app.utils.applyPreferenceDialogStyle

class MultiSelectListPreferenceDialog : MultiSelectListPreferenceDialogFragmentCompat() {

    companion object {

        fun newInstance(key: String?): MultiSelectListPreferenceDialog {
            val fragment =
                MultiSelectListPreferenceDialog()
            val b = Bundle(1)
            b.putString(ARG_KEY, key)
            fragment.arguments = b
            return fragment
        }

    }


    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.applyPreferenceDialogStyle(requireContext(), tintListView = true)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        if (AppConfig.isEInkMode) {
            dialog?.window?.applyEInkDialogStyle()
        }
    }

}
