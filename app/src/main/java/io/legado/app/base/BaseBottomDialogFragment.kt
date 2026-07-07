package io.legado.app.base

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.LayoutRes
import io.legado.app.R

abstract class BaseBottomDialogFragment(
    @LayoutRes layoutID: Int
) : BaseDialogFragment(layoutID) {

    open val dismissWhenOtherBottomDialogShowing: Boolean = false
    private var counterIncremented = false

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    final override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bottomDialogActivity = activity as? IBottomDialog
        if (dismissWhenOtherBottomDialogShowing && (bottomDialogActivity?.bottomDialog ?: 0) > 0) {
            dismiss()
            return
        }
        bottomDialogActivity?.let { it.bottomDialog++ }
        counterIncremented = true
        onBottomDialogCreated(view, savedInstanceState)
    }

    abstract fun onBottomDialogCreated(view: View, savedInstanceState: Bundle?)

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (counterIncremented) {
            (activity as? IBottomDialog)?.let { it.bottomDialog-- }
            counterIncremented = false
        }
    }
}
