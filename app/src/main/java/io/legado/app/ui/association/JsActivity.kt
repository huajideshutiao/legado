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
import com.script.quickjs.JsFunction
import com.script.quickjs.QuickJsEngine
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ViewEmptyBinding
import io.legado.app.help.IntentData
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Suppress("unused")
open class JsActivity : BaseActivity<ViewEmptyBinding>() {
    override val binding by viewBinding(ViewEmptyBinding::inflate)

    /**
     * 当前 Activity 关联的 QuickJsContext。
     *
     * 用于:
     * 1. 在 Activity 内创建/调用 JS function 时,提供 ThreadLocal 上下文
     * 2. 关联 lifecycleScope 的协程上下文,支持 ensureActive 取消
     * 3. dangerousApi/recursiveCount 等安全控制
     *
     * 注意: JsFunction 自身持有原 scope 的 QuickJsContext(从 IntentData 取出),
     * 调用 fn.call() 时会在原 scope 上 evaluate,不使用此处的 cx。
     * 此处的 cx 主要用于 Activity 内创建的新 JS function(如 setBackEvent 的回调)。
     */
    private val cx by lazy { QuickJsEngine.createQuickJsForActivity() }
    private var error: Throwable? = null

    val dialog by lazy {
        BottomSheetDialog(this).apply {
            setContentView(
                NestedScrollView(this@JsActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        resources.displayMetrics.heightPixels
                    )
                    setPadding(0, 20.dpToPx(), 0, 0)
                    addView(dialogView)
                }
            )
            val bottomSheet =
                findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            dismissWithAnimation = true
            behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
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
     *
     * 在 quickjs 下:
     * - allowScriptRun/recursiveCount 仍保留以兼容 cx 字段语义
     * - dangerousApi 由 JsFunction 自身控制,这里不修改
     * - 调用 fn.call(*args) 即可,JsFunction 内部会处理 ThreadLocal 设置
     */
    @JvmOverloads
    fun runWithAuth(fn: JsFunction, args: Array<Any?> = emptyArray()): Any? {
        // 关联 lifecycleScope 协程上下文,支持取消
        if (lifecycleScope.coroutineContext[Job] != null) {
            cx.coroutineContext = lifecycleScope.coroutineContext
        }
        cx.allowScriptRun = true
        cx.recursiveCount++
        try {
            cx.checkRecursive()
        } catch (e: Throwable) {
            cx.recursiveCount--
            return e
        }
        return try {
            fn.call(*args)
        } catch (e: Throwable) {
            error = e
            finish()
            e
        } finally {
            cx.recursiveCount--
        }
    }

    /**
     * 设置返回按钮点击事件处理程序
     */
    fun setBackEvent(
        target: OnBackPressedDispatcherOwner,
        func: JsFunction
    ): OnBackPressedCallback {
        val tmp = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                runWithAuth(func)
            }
        }
        target.onBackPressedDispatcher.addCallback(target, tmp)
        return tmp
    }

    /**
     * 获取一个带授权的 Runnable。
     * 供 Thread(runnable) 或 Handler.post(runnable) 使用。
     */
    @JvmOverloads
    fun getAuthRunnable(fn: JsFunction, args: Array<Any?> = emptyArray()): Runnable {
        return Runnable {
            runWithAuth(fn, args)
        }
    }

    /**
     * 在协程中授权运行
     */
    fun launch(fn: JsFunction) {
        lifecycleScope.launch(Dispatchers.IO) {
            runWithAuth(fn)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // 不支持重建，因为 JsFunction 持有 QuickJs 实例无法序列化
        if (savedInstanceState != null) {
            finish()
            return
        }
        val actionKey = intent.getStringExtra("actionKey")
        if (actionKey != null) {
            IntentData.get<JsFunction>(actionKey)?.let { action ->
                // 把 this(JsActivity) 作为参数传入,JsFunction.call 内部会通过
                // JavaObjectBridge.registerObject 注册句柄并包装为 JS Proxy
                // 对应 rhino 的 cx.wrapFactory.wrap(cx, scope, this, JsActivity::class.java)
                runWithAuth(action, arrayOf(this))
                return
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
            // 关闭 Activity 关联的 native QuickJs ctx
            cx.close()
            it.invoke(error)
        }
    }
}

class JsActivity1 : JsActivity()
class JsActivity2 : JsActivity()
