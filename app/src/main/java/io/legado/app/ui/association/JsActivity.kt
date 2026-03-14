package io.legado.app.ui.association

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.script.rhino.RhinoContext
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ViewEmptyBinding
import io.legado.app.help.IntentData
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isMainThread
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function

@Suppress("unused")
open class JsActivity : BaseActivity<ViewEmptyBinding>() {
    override val binding by viewBinding(ViewEmptyBinding::inflate)
    private val cx by lazy { Context.enter() as RhinoContext }
    private var error: Throwable? = null

    val dialog by lazy {
        val displayHeight = resources.displayMetrics.heightPixels
        BottomSheetDialog(this).apply {
            setContentView(
                NestedScrollView(this@JsActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        displayHeight
                    )
                    setPadding(0, 20.dpToPx(), 0, 0)
                    addView(dialogView)
                }
            )
            val bottomSheet =
                findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            dismissWithAnimation = true
            behavior.peekHeight = (displayHeight * 0.6).toInt()
            bottomSheet?.background = GradientDrawable().apply {
                setColor(backgroundColor)
                val radius = 20f.dpToPx()
                // 数组分别代表：左上x, 左上y, 右上x, 右上y, 右下x... (顺时针)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            setOnDismissListener {
                if (!isFinishing) finish()
            }
        }
    }

    val dialogView by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /**
     * 授权执行方法，确保脚本具有运行权限和危险 API 访问权限
     */
    @JvmOverloads
    fun runWithAuth(fn: Function, args: Array<Any?> = emptyArray()): Any? {
        val currentCx = if (isMainThread) cx else Context.enter() as RhinoContext
        try {
            currentCx.allowScriptRun = true
            currentCx.dangerousApi = true
            currentCx.recursiveCount++
            currentCx.checkRecursive()
            if (currentCx != cx) {
                if (lifecycleScope.coroutineContext[Job] != null) {
                    currentCx.coroutineContext = lifecycleScope.coroutineContext
                }
            }
            return fn.call(currentCx, fn.parentScope, fn.parentScope, args)
        } finally {
            currentCx.recursiveCount--
            if (currentCx != cx) {
                Context.exit()
            }
        }
    }

    /**
     * 设置返回按钮点击事件处理程序
     */
    fun setBackEvent(target: OnBackPressedDispatcherOwner, func: Function) {
        target.onBackPressedDispatcher.addCallback(target, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                runWithAuth(func, arrayOf(this))
            }
        })
    }

    /**
     * 获取一个带授权的 Runnable。
     * 供 Thread(runnable) 或 Handler.post(runnable) 使用。
     */
    @JvmOverloads
    fun getAuthRunnable(fn: Function, args: Array<Any?> = emptyArray()): Runnable {
        return Runnable {
            runWithAuth(fn, args)
        }
    }

    /**
     * 在协程中授权运行
     */
    fun launch(fn: Function) {
        lifecycleScope.launch(Dispatchers.IO) {
            runWithAuth(fn)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // 不支持重建，因为 Rhino Function 无法序列化
        if (savedInstanceState != null) {
            finish()
            return
        }
        val actionKey = intent.getStringExtra("actionKey")
        if (actionKey != null) {
            IntentData.get<Function>(actionKey)?.let { action ->
                try {
                    runWithAuth(action, arrayOf(this))
                    return
                } catch (e: Throwable) {
                    error = e
                }
            }
        }
        finish()
    }

    override fun finish() {
        super.finish()
        clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        clear()
    }

    private fun clear() {
        val waitKey = intent.getStringExtra("waitKey")
        val action = IntentData.get<(Throwable?) -> Unit>(waitKey)
        action?.let {
            cx.allowScriptRun = false
            cx.dangerousApi = false
            Context.exit()
            it.invoke(error)
        }
    }
}

class JsActivity1 : JsActivity()
class JsActivity2 : JsActivity()
