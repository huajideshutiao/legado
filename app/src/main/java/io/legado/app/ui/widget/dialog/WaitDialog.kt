package io.legado.app.ui.widget.dialog

import android.content.Context
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.legado.app.R
import io.legado.app.lib.dialogs.customView
import io.legado.app.utils.applyTint

class WaitDialog(context: Context) {

    private val tvMsg: TextView
    private val dialog: AlertDialog

    var onCancelListener: (() -> Unit)? = null

    init {
        val dp30 = (30 * context.resources.displayMetrics.density).toInt()
        val dp16 = (16 * context.resources.displayMetrics.density).toInt()
        val dp8 = (8 * context.resources.displayMetrics.density).toInt()

        val progressBar = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp30, dp30)
        }

        tvMsg = TextView(context).apply {
            val pad = dp8
            setPadding(pad, pad, pad, pad)
            setTextColor(ContextCompat.getColor(context, R.color.primaryText))
            text = context.getString(R.string.loading)
            gravity = android.view.Gravity.CENTER
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp16, dp16, dp16, dp16)
            addView(progressBar)
            addView(tvMsg)
        }

        dialog = AlertDialog.Builder(context).apply {
            customView { container }
        }.create()
        dialog.applyTint()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener {
            onCancelListener?.invoke()
        }
    }

    fun setText(text: String): WaitDialog {
        tvMsg.text = text
        return this
    }

    fun setText(@StringRes res: Int): WaitDialog {
        tvMsg.text = dialog.context.getString(res)
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
