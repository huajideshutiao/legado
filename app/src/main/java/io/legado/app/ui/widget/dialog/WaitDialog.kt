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
    private var msg: String? = null
    var onCancelListener: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val msg = savedInstanceState?.getString("msg") ?: msg
        msg?.let { binding.tvMsg.text = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("msg", msg)
    }

    fun setText(text: String): WaitDialog {
        msg = text
        if (isAdded) {
            binding.tvMsg.text = text
        }
        return this
    }

    fun setText(res: Int): WaitDialog {
        msg = getString(res)
        if (isAdded) {
            binding.tvMsg.text = msg
        }
        return this
    }

    fun show(manager: FragmentManager) {
        show(manager, "waitDialog")
    }

    fun dismissSafe() {
        kotlin.runCatching {
            if (isAdded) {
                dismissAllowingStateLoss()
            }
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
