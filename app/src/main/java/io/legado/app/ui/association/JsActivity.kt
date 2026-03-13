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

class JsActivity : BaseActivity<ViewEmptyBinding>() {
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
            }
        }
        finish()
    }

    override fun finish() {
        super.finish()
        val waitKey = intent.getStringExtra("waitKey")
        val action = IntentData.get<() -> Unit>(waitKey)
        action?.invoke()
        Context.exit()
    }
}
