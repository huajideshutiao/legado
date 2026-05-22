package io.legado.app.ui.widget.dialog

import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.legado.app.databinding.DialogWaitBinding
import io.legado.app.lib.dialogs.customView
import io.legado.app.utils.applyTint

class WaitDialog(context: Context) {

    private val binding = DialogWaitBinding.inflate(LayoutInflater.from(context))
    private val dialog: AlertDialog = AlertDialog.Builder(context).apply {
        customView { binding.root }
    }.create()

    var onCancelListener: (() -> Unit)? = null

    init {
        dialog.applyTint()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener {
            onCancelListener?.invoke()
        }
    }

    fun setText(text: String): WaitDialog {
        binding.tvMsg.text = text
        return this
    }

    fun setText(@StringRes res: Int): WaitDialog {
        binding.tvMsg.text = dialog.context.getString(res)
        return this
    }

    @Suppress("UNUSED_PARAMETER")
    fun show(manager: FragmentManager? = null) {
        if (!dialog.isShowing) {
            kotlin.runCatching {
                dialog.show()
            }
        }
    }

    fun dismissSafe() {
        kotlin.runCatching {
            dialog.dismiss()
        }
    }

    companion object {
        private val dialogMap = mutableMapOf<Int, WaitDialog>()

        fun from(activity: FragmentActivity): WaitDialog {
            val hashCode = activity.hashCode()
            var waitDialog = dialogMap[hashCode]
            if (waitDialog == null || !waitDialog.dialog.isShowing) {
                waitDialog = WaitDialog(activity)
                dialogMap[hashCode] = waitDialog
                activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) {
                        waitDialog.dismissSafe()
                        dialogMap.remove(hashCode)
                    }
                })
            }
            return waitDialog
        }

        fun dismiss(activity: FragmentActivity?) {
            activity?.let {
                dialogMap[it.hashCode()]?.dismissSafe()
                dialogMap.remove(it.hashCode())
            }
        }
    }
}
