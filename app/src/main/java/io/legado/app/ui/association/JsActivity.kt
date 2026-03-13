package io.legado.app.ui.association

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.script.rhino.RhinoContext
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ViewEmptyBinding
import io.legado.app.help.IntentData
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Job
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function

open class JsActivity : BaseActivity<ViewEmptyBinding>() {
    override val binding by viewBinding(ViewEmptyBinding::inflate)
    private val cx by lazy { Context.enter() as RhinoContext }
    private var error: Throwable? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val actionKey = intent.getStringExtra("actionKey")
        if (actionKey != null) {
            IntentData.get<Function>(actionKey)?.let { action ->
                val previousCoroutineContext = cx.coroutineContext
                if (lifecycleScope.coroutineContext[Job] != null) {
                    cx.coroutineContext = lifecycleScope.coroutineContext
                }
                cx.allowScriptRun = true
                cx.dangerousApi = true
                cx.recursiveCount++
                try {
                    cx.checkRecursive()
                    val scope = action.parentScope
                    val jsThis =
                        cx.wrapFactory.wrap(cx, scope, this, JsActivity::class.java)
                    action.call(cx, scope, scope, arrayOf(jsThis))
                    return
                } catch (e: Throwable) {
                    error = e
                } finally {
                    cx.coroutineContext = previousCoroutineContext
                    cx.recursiveCount--
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
            cx.dangerousApi = true
            Context.exit()
            it.invoke(error)
        }
    }
}

class JsActivity1 : JsActivity()
class JsActivity2 : JsActivity()
