package io.legado.app.ui.association

import android.os.Bundle
import com.script.rhino.RhinoContext
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ViewEmptyBinding
import io.legado.app.help.IntentData
import io.legado.app.utils.viewbindingdelegate.viewBinding
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function

open class JsActivity : BaseActivity<ViewEmptyBinding>() {
    override val binding by viewBinding(ViewEmptyBinding::inflate)
    private val cx by lazy {
        Context.enter() as RhinoContext
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val actionKey = intent.getStringExtra("actionKey")
        if (actionKey != null) {
            IntentData.get<Function>(actionKey)?.let { action ->
                cx.allowScriptRun = true
                cx.dangerousApi = true
                try {
                    val scope = action.parentScope
                    val jsThis =
                        cx.wrapFactory.wrap(cx, scope, this, JsActivity::class.java)
                    action.call(cx, scope, scope, arrayOf(jsThis))
                    return
                } catch (e: Exception) {
                    AppLog.put("JsActivity执行JS失败\n${e.localizedMessage}", e, true)
                }
            } ?: finish()
        } else {
            finish()
        }
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
        val action = IntentData.get<() -> Unit>(waitKey)
        action?.let {
            it.invoke()
            Context.exit()
        }
    }
}

class JsActivity1 : JsActivity()
class JsActivity2 : JsActivity()
