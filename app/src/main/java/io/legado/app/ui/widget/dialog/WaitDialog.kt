package io.legado.app.ui.widget.dialog

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogWaitBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding

class WaitDialog : BaseDialogFragment(R.layout.dialog_wait) {

    private val binding by viewBinding(DialogWaitBinding::bind)
    private var pendingText: String? = null
    private var pendingRes: Int? = null
    var onCancelListener: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        pendingText?.let { binding.tvMsg.text = it }
        pendingRes?.let { binding.tvMsg.setText(it) }
        pendingText = null
        pendingRes = null
    }

    fun setText(text: String): WaitDialog {
        pendingText = text
        pendingRes = null
        if (isAdded) {
            binding.tvMsg.text = text
        }
        return this
    }

    fun setText(res: Int): WaitDialog {
        pendingRes = res
        pendingText = null
        if (isAdded) {
            binding.tvMsg.setText(res)
        }
        return this
    }

    fun show(manager: FragmentManager) {
        show(manager, "waitDialog")
    }

    fun dismissSafe() {
        if (isAdded) {
            dismiss()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        onCancelListener?.invoke()
    }

    companion object {
        fun from(activity: FragmentActivity): WaitDialog {
            val fm = activity.supportFragmentManager
            var dialog = fm.findFragmentByTag("waitDialog") as? WaitDialog
            if (dialog == null) {
                dialog = WaitDialog()
            }
            return dialog
        }
    }
}
