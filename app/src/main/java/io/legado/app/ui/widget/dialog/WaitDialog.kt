package io.legado.app.ui.widget.dialog

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.legado.app.help.i18n.androidAppString
import io.legado.app.base.ComposeDialog

class WaitDialog(context: Context) {

    // 文本用 Compose 状态承载，setText 可从任意线程写入（Snapshot 线程安全），读侧自动重组
    private var message by mutableStateOf(androidAppString("loading"))
    private val dialog = ComposeDialog(context, fullWidth = false)

    var onCancelListener: (() -> Unit)? = null

    init {
        dialog.setComposeContent {
            // UI 下沉到 shared/sharedUiMain 的 WaitDialogContent (app/desktop/iOS 共用, 样式逐项对齐)
            WaitDialogContent(message)
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onCancelListener?.invoke() }
    }

    fun setText(text: String): WaitDialog {
        message = text
        return this
    }

    fun setText(@StringRes res: Int): WaitDialog {
        message = dialog.context.getString(res)
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
