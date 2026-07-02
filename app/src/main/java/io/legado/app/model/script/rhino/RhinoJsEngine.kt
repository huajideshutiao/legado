package io.legado.app.model.script.rhino

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.model.script.JsBindings
import io.legado.app.model.script.JsCompiledScript
import io.legado.app.model.script.JsEngine
import io.legado.app.model.script.JsEngineType
import io.legado.app.model.script.JsFn
import io.legado.app.model.script.JsScope
import io.legado.app.model.script.rhino.RhinoJsEngine.cleanupBindings
import io.legado.app.model.script.rhino.RhinoJsEngine.evalBytecode
import io.legado.app.model.script.rhino.RhinoJsEngine.evalInSubScope
import io.legado.app.model.script.rhino.RhinoJsEngine.injectBindings
import io.legado.app.model.script.rhino.RhinoJsEngine.toRhinoBindings
import io.legado.app.model.script.rhino.RhinoJsEngine.wrapJsForEval
import org.mozilla.javascript.Scriptable
import kotlin.coroutines.CoroutineContext

/**
 * rhino 引擎实现：委托 [RhinoScriptEngine]（object 单例）。
 *
 * 对应 quickjs 的 [io.legado.app.model.script.quickjs.QuickJsJsEngine]。
 * 业务层通过 [io.legado.app.model.script.JsEngines.get] 获取当前引擎。
 *
 * 用 rhino 语义实现 quickjs 独有 API：
 * - [wrapJsForEval]: 直接返回源码（rhino 原生支持顶层 return + 子 scope 词法隔离，无需 IIFE 包装）
 * - [evalInSubScope]: 创建子 ScriptBindings（prototype=sharedScope）后 compiled.eval
 *   （rhino 子 scope 出栈自动清理，无需 cleanup）
 * - [injectBindings] / [cleanupBindings]: 空实现（rhino 子 scope prototype 自动管理变量生命周期）
 * - [evalBytecode]: 抛 UnsupportedOperationException（rhino 无 bytecode 概念，调用方不会走此路径）
 *
 * [toRhinoBindings] 把 app 侧 [JsBindings]（纯 Map）转换为 rhino 的 [com.script.ScriptBindings]
 * （继承 NativeObject，持 dangerousApi 标志）。
 */
object RhinoJsEngine : JsEngine {

    override val type: JsEngineType = JsEngineType.RHINO

    override fun eval(js: String, bindingsConfig: JsBindings.() -> Unit): Any? =
        RhinoScriptEngine.eval(js, toRhinoBindings(JsBindings().apply(bindingsConfig)))

    override fun eval(js: String, bindings: JsBindings): Any? =
        RhinoScriptEngine.eval(js, toRhinoBindings(bindings))

    override fun getRuntimeScope(bindings: JsBindings): JsScope {
        val scriptable = RhinoScriptEngine.getRuntimeScope(toRhinoBindings(bindings))
        return RhinoJsScope(scriptable)
    }

    /**
     * 创建独立 scope（JsActivity 用）。
     *
     * rhino 无 Activity 独占概念，复用 [RhinoScriptEngine.getRuntimeScope] 创建新 scope。
     * 对应 quickjs 的 `QuickJsEngine.createQuickJsForActivity()`。
     */
    override fun createStandaloneScope(): JsScope {
        val scriptable = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
        return RhinoJsScope(scriptable)
    }

    override fun compile(script: String): JsCompiledScript =
        RhinoJsCompiledScript(RhinoScriptEngine.compile(script))

    /**
     * 在指定 scope 上编译（rhino 编译不依赖 scope，scope 参数忽略）。
     *
     * rhino 的 [com.script.rhino.RhinoScriptEngine.compile] 只接收 script，
     * 编译产物可在任意 scope 上 eval（与 quickjs 不同，quickjs bytecode 也可跨 ctx）。
     */
    override fun compile(script: String, scope: JsScope): JsCompiledScript =
        RhinoJsCompiledScript(RhinoScriptEngine.compile(script))

    /**
     * 把 JS 包成可子 scope 执行的形式。
     *
     * quickjs 用 `(function(){return eval(<源码>);})()` 防止 topScope 变量污染；
     * rhino 原生支持顶层 return + 子 scope 词法隔离，直接返回源码。
     */
    override fun wrapJsForEval(jsStr: String): String = jsStr

    override fun compileForSubScope(jsStr: String): JsCompiledScript =
        compile(wrapJsForEval(jsStr))

    /**
     * 在共享 topScope 上执行 compiled，bindings 注入子 scope。
     *
     * rhino 实现：创建子 [ScriptBindings]，prototype 设为 sharedScope.scriptable
     * （子 scope 通过原型链继承 topScope 变量），注入 bindings.entries，
     * 然后 compiled.eval(childScope, ctx)。
     *
     * 子 scope 出栈后自动清理（GC 回收），无需 cleanup。
     * 对应 quickjs 的 snapshot + inject + eval + pop 机制。
     */
    override fun evalInSubScope(
        compiled: JsCompiledScript,
        scope: JsScope,
        bindings: JsBindings,
        coroutineContext: CoroutineContext?
    ): Any? {
        val rhinoScope = scope as? RhinoJsScope
            ?: error("RhinoJsEngine.evalInSubScope requires RhinoJsScope, got ${scope::class}")
        val scriptable = rhinoScope.scriptable
            ?: error("RhinoJsScope.scriptable is null, cannot evalInSubScope on a context-only scope")
        val rhinoCompiled = (compiled as? RhinoJsCompiledScript)?.delegate
            ?: error("RhinoJsEngine.evalInSubScope requires RhinoJsCompiledScript, got ${compiled::class}")
        // 创建子 ScriptBindings，prototype 指向共享 scope（子 scope 继承 topScope 变量）
        val childScope = ScriptBindings().apply {
            this.dangerousApi = bindings.dangerousApi
            prototype = scriptable
            bindings.forEach { (k, v) -> this[k] = v }
        }
        return rhinoCompiled.eval(childScope, coroutineContext)
    }

    override fun eval(js: String, scope: JsScope, coroutineContext: CoroutineContext?): Any? {
        val scriptable = requireScriptable(scope)
        return RhinoScriptEngine.eval(js, scriptable, coroutineContext)
    }

    override fun eval(
        js: String,
        scope: JsScope,
        bindings: JsBindings,
        coroutineContext: CoroutineContext?
    ): Any? {
        val scriptable = requireScriptable(scope)
        // 创建子 ScriptBindings（prototype=scope），注入 bindings，一次性 eval
        // 对应 quickjs 的 eval(js, scope, bindings, ctx)（injectBindings + eval）
        val childScope = ScriptBindings().apply {
            this.dangerousApi = bindings.dangerousApi
            prototype = scriptable
            bindings.forEach { (k, v) -> this[k] = v }
        }
        return RhinoScriptEngine.eval(js, childScope, coroutineContext)
    }

    /**
     * 执行 bytecode（rhino 不支持 bytecode）。
     *
     * rhino 下 [JsCompiledScript.bytecode] 恒 null，调用方不会走此路径。
     * 抛异常以防误用。
     */
    override fun evalBytecode(
        bytecode: ByteArray?,
        scope: JsScope,
        coroutineContext: CoroutineContext?
    ): Any? {
        throw UnsupportedOperationException("rhino 不支持 bytecode，请用 JsCompiledScript.eval")
    }

    /**
     * 注入 bindings 到 scope（rhino 空实现）。
     *
     * rhino 子 scope 通过 prototype 继承自动管理变量生命周期，
     * 不需要像 quickjs 那样手动注入 globalThis + 清理。
     * 返回空 list（[cleanupBindings] 也是空实现）。
     */
    override fun injectBindings(scope: JsScope, bindings: JsBindings): List<String> = emptyList()

    /** 清理 injectBindings 注入的变量（rhino 空实现，子 scope 关闭自动清理）。 */
    override fun cleanupBindings(scope: JsScope, keys: List<String>) {
        // 空实现：rhino 子 scope 出栈后 GC 自动清理，无需手动 delete
    }

    override fun wrapJsFn(scope: JsScope, functionExpr: String, dangerousApi: Boolean): JsFn {
        val rhinoScope = scope as? RhinoJsScope
            ?: error("RhinoJsEngine.wrapJsFn requires RhinoJsScope, got ${scope::class}")
        return RhinoJsFn(rhinoScope, functionExpr, dangerousApi)
    }

    /**
     * 从 [JsScope] 提取 [Scriptable]（用于 eval）。
     *
     * RhinoJsScope.scriptable 可空（从 jsContext 取时为 null），
     * eval 路径必须非 null。
     */
    private fun requireScriptable(scope: JsScope): Scriptable {
        val rhinoScope = scope as? RhinoJsScope
            ?: error("RhinoJsEngine requires RhinoJsScope, got ${scope::class}")
        return rhinoScope.scriptable
            ?: error("RhinoJsScope.scriptable is null, cannot eval on a context-only scope")
    }

    /**
     * 把 app 侧 [JsBindings]（纯 Map + dangerousApi）转换为 rhino 的 [ScriptBindings]。
     *
     * ScriptBindings 继承 NativeObject，持 dangerousApi 标志，
     * RhinoScriptEngine 各方法都接收 ScriptBindings（非 JsBindings）。
     */
    private fun toRhinoBindings(jb: JsBindings): ScriptBindings = ScriptBindings().apply {
        dangerousApi = jb.dangerousApi
        jb.forEach { (k, v) -> this[k] = v }
    }
}
