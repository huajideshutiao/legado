package io.legado.app.model.script.quickjs

import com.script.quickjs.QuickJsEngine
import com.script.quickjs.ScriptBindings
import io.legado.app.model.script.JsBindings
import io.legado.app.model.script.JsCompiledScript
import io.legado.app.model.script.JsEngine
import io.legado.app.model.script.JsEngineType
import io.legado.app.model.script.JsFn
import io.legado.app.model.script.JsScope
import io.legado.app.model.script.quickjs.QuickJsJsEngine.toQuickJsBindings
import kotlin.coroutines.CoroutineContext

/**
 * quickjs 引擎实现：直接转发 [QuickJsEngine] 静态方法。
 *
 * 对应 rhino 的 [io.legado.app.model.script.rhino.RhinoJsEngine]。
 * 业务层通过 [io.legado.app.model.script.JsEngines.get] 获取当前引擎，
 * 不再直接调用 `QuickJsEngine`。
 *
 * [toQuickJsBindings] 把 app 侧 [JsBindings]（纯 Map）转换为 quickjs 的
 * `com.script.quickjs.ScriptBindings`（继承 LinkedHashMap，持 dangerousApi 标志）。
 */
object QuickJsJsEngine : JsEngine {

    override val type: JsEngineType = JsEngineType.QUICKJS

    override fun eval(js: String, bindingsConfig: JsBindings.() -> Unit): Any? =
        QuickJsEngine.eval(js, toQuickJsBindings(JsBindings().apply(bindingsConfig)))

    override fun eval(js: String, bindings: JsBindings): Any? =
        QuickJsEngine.eval(js, toQuickJsBindings(bindings))

    override fun getRuntimeScope(bindings: JsBindings): JsScope =
        QuickJsJsScope(QuickJsEngine.getRuntimeScope(toQuickJsBindings(bindings)))

    override fun createStandaloneScope(): JsScope =
        QuickJsJsScope(QuickJsEngine.createQuickJsForActivity())

    override fun compile(script: String): JsCompiledScript =
        QuickJsJsCompiledScript(QuickJsEngine.compile(script))

    override fun compile(script: String, scope: JsScope): JsCompiledScript {
        val qScope = scope as? QuickJsJsScope
            ?: error("QuickJsJsEngine.compile requires QuickJsJsScope, got ${scope::class}")
        return QuickJsJsCompiledScript(QuickJsEngine.compile(script, qScope.ctx))
    }

    override fun wrapJsForEval(jsStr: String): String = QuickJsEngine.wrapJsForEval(jsStr)

    override fun compileForSubScope(jsStr: String): JsCompiledScript =
        QuickJsJsCompiledScript(QuickJsEngine.compileForSubScope(jsStr))

    /**
     * 在共享 topScope 上执行包装后的 compiled，bindings 注入子 scope。
     *
     * 直接转发 [QuickJsEngine.evalInSubScope]，内部已含：
     * pushBindingSnapshot → injectBindings → evalBytecode → popBindingSnapshot
     * （quickjs 无 prototype 链，用快照/恢复模拟 rhino 子 scope 隔离）。
     *
     * 注意：不能在此处再手动调 injectBindings/cleanupBindings，会与 evalInSubScope
     * 内部的 snapshot 机制冲突。
     */
    override fun evalInSubScope(
        compiled: JsCompiledScript,
        scope: JsScope,
        bindings: JsBindings,
        coroutineContext: CoroutineContext?
    ): Any? {
        val qScope = scope as? QuickJsJsScope
            ?: error("QuickJsJsEngine.evalInSubScope requires QuickJsJsScope, got ${scope::class}")
        val qCompiled = (compiled as? QuickJsJsCompiledScript)?.delegate
            ?: error("QuickJsJsEngine.evalInSubScope requires QuickJsJsCompiledScript, got ${compiled::class}")
        return QuickJsEngine.evalInSubScope(
            qCompiled,
            qScope.ctx,
            toQuickJsBindings(bindings),
            coroutineContext
        )
    }

    override fun eval(js: String, scope: JsScope, coroutineContext: CoroutineContext?): Any? {
        val qScope = scope as? QuickJsJsScope
            ?: error("QuickJsJsEngine.eval requires QuickJsJsScope, got ${scope::class}")
        return QuickJsEngine.eval(js, qScope.ctx, coroutineContext)
    }

    override fun eval(
        js: String,
        scope: JsScope,
        bindings: JsBindings,
        coroutineContext: CoroutineContext?
    ): Any? {
        val qScope = scope as? QuickJsJsScope
            ?: error("QuickJsJsEngine.eval requires QuickJsJsScope, got ${scope::class}")
        return QuickJsEngine.eval(js, qScope.ctx, toQuickJsBindings(bindings), coroutineContext)
    }

    override fun evalBytecode(
        bytecode: ByteArray?,
        scope: JsScope,
        coroutineContext: CoroutineContext?
    ): Any? {
        if (bytecode == null) return null
        val qScope = scope as? QuickJsJsScope
            ?: error("QuickJsJsEngine.evalBytecode requires QuickJsJsScope, got ${scope::class}")
        return QuickJsEngine.evalBytecode(bytecode, qScope.ctx, coroutineContext)
    }

    override fun injectBindings(scope: JsScope, bindings: JsBindings): List<String> {
        val qScope = scope as? QuickJsJsScope
            ?: error("QuickJsJsEngine.injectBindings requires QuickJsJsScope, got ${scope::class}")
        return QuickJsEngine.injectBindings(qScope.ctx, toQuickJsBindings(bindings))
    }

    override fun cleanupBindings(scope: JsScope, keys: List<String>) {
        val qScope = scope as? QuickJsJsScope
            ?: error("QuickJsJsEngine.cleanupBindings requires QuickJsJsScope, got ${scope::class}")
        QuickJsEngine.cleanupBindings(qScope.ctx, keys)
    }

    override fun wrapJsFn(scope: JsScope, functionExpr: String, dangerousApi: Boolean): JsFn {
        val qScope = scope as? QuickJsJsScope
            ?: error("QuickJsJsEngine.wrapJsFn requires QuickJsJsScope, got ${scope::class}")
        return QuickJsJsFn(qScope.ctx, functionExpr, dangerousApi)
    }

    /**
     * 把 app 侧 [JsBindings]（纯 Map + dangerousApi）转换为 quickjs 的 [ScriptBindings]。
     *
     * ScriptBindings 继承 LinkedHashMap，持 dangerousApi 标志，
     * QuickJsEngine 各方法都接收 ScriptBindings（非 JsBindings）。
     */
    private fun toQuickJsBindings(jb: JsBindings): ScriptBindings = ScriptBindings().apply {
        dangerousApi = jb.dangerousApi
        jb.forEach { (k, v) -> this[k] = v }
    }
}
