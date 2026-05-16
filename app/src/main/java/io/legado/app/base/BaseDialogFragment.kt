package io.legado.app.base

import android.content.DialogInterface
import android.content.DialogInterface.OnDismissListener
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.annotation.LayoutRes
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.filletBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext


abstract class BaseDialogFragment(
    @LayoutRes layoutID: Int
) : DialogFragment(layoutID) {

    protected open val applyFilletBackground: Boolean = true

    private var onDismissListener: OnDismissListener? = null

    fun setOnDismissListener(onDismissListener: OnDismissListener?) {
        this.onDismissListener = onDismissListener
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let {
            it.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            if (view != null) {
                it.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
            }
            val attr = it.attributes
            if (AppConfig.isEInkMode) {
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attr.dimAmount = 0.0f
                attr.windowAnimations = 0
            } else {
                attr.windowAnimations = R.style.Animation_Dialog
            }
            it.attributes = attr
            val width = (resources.displayMetrics.widthPixels * 0.95).toInt()
            it.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!AppConfig.isEInkMode && applyFilletBackground) {
            view.background = requireContext().filletBackground
        }
        val toolbar = view.findViewById<View>(R.id.tool_bar)
        if (toolbar != null && !AppConfig.isEInkMode) {
            toolbar.setBackgroundColor(requireContext().backgroundColor)
        }
        onFragmentCreated(view, savedInstanceState)
        observeLiveBus()
    }

    abstract fun onFragmentCreated(view: View, savedInstanceState: Bundle?)

    override fun show(manager: FragmentManager, tag: String?) {
        kotlin.runCatching {
            //在每个add事务前增加一个remove事务，防止连续的add
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }.onFailure {
            AppLog.put("显示对话框失败 tag:$tag", it)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    fun <T> execute(
        scope: CoroutineScope = lifecycleScope,
        context: CoroutineContext = Dispatchers.IO,
        block: suspend CoroutineScope.() -> T
    ) = Coroutine.async(scope, context) { block() }

    open fun observeLiveBus() {
    }
}