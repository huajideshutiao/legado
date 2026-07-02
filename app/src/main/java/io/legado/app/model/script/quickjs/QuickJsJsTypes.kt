package io.legado.app.model.script.quickjs

import com.script.quickjs.CompiledScript
import com.script.quickjs.JsFunction
import com.script.quickjs.NativeObject
import com.script.quickjs.QuickJsContext
import io.legado.app.model.script.JsCompiledScript
import io.legado.app.model.script.JsFn
import io.legado.app.model.script.JsObject
import io.legado.app.model.script.JsScope
import kotlin.coroutines.CoroutineContext

/**
 * quickjs scope 包装：直接委托 [QuickJsContext]。
 *
 * 对应 rhino 的 [io.legado.app.model.script.rhino.RhinoJsScope]。
 * QuickJsContext 持 native ctx 指针，线程独占，AutoCloseable。
 *
 * 所有 JsScope 属性/方法直接转发到 ctx（coroutineContext/allowScriptRun/recursiveCount/ensureActive/checkRecursive/close）。
 */
class QuickJsJsScope(val ctx: QuickJsContext) : JsScope {

    override var coroutineContext: CoroutineContext?
        get() = ctx.coroutineContext
        set(value) {
            ctx.coroutineContext = value
        }

    override var allowScriptRun: Boolean
        get() = ctx.allowScriptRun
        set(value) {
            ctx.allowScriptRun = value
        }

    override var recursiveCount: Int
        get() = ctx.recursiveCount
        set(value) {
            ctx.recursiveCount = value
        }

    override fun ensureActive() = ctx.ensureActive()

    override fun checkRecursive() = ctx.checkRecursive()

    override fun close() = ctx.close()
}

/**
 * quickjs 编译脚本包装：委托 [com.script.quickjs.CompiledScript]。
 *
 * [bytecode] 非 null（QuickJS bytecode，可缓存复用，跨 ctx 兼容）。
 * 对应 rhino 的 [io.legado.app.model.script.rhino.RhinoJsCompiledScript]（bytecode 恒 null）。
 */
class QuickJsJsCompiledScript(val delegate: CompiledScript) : JsCompiledScript {

    override val bytecode: ByteArray?
        get() = delegate.bytecode

    override fun eval(scope: JsScope, coroutineContext: CoroutineContext?): Any? {
        val qScope = scope as? QuickJsJsScope
            ?: error("QuickJsJsCompiledScript requires QuickJsJsScope, got ${scope::class}")
        return delegate.eval(qScope.ctx, coroutineContext)
    }
}

/**
 * quickjs NativeObject 包装。
 *
 * `com.script.quickjs.NativeObject` 继承 `LinkedHashMap<String, Any?>`，
 * 直接 by delegate 把 MutableMap 方法委托给 NativeObject，
 * 业务层 `jsObj[rule]` 走 Map 索引（与 rhino RhinoJsObject 行为一致）。
 */
class QuickJsJsObject(val delegate: NativeObject) : JsObject, MutableMap<String, Any?> by delegate

/**
 * quickjs JsFn 包装：委托 [com.script.quickjs.JsFunction]。
 *
 * 对应 rhino 的 [io.legado.app.model.script.rhino.RhinoJsFn]。
 * call 时通过 JsFunction nativeEval 执行 `($functionExpr)(args)`。
 */
class QuickJsJsFn(
    private val ctx: QuickJsContext,
    private val functionExpr: String,
    override val dangerousApi: Boolean = false
) : JsFn {

    private val delegate = JsFunction(ctx, functionExpr, dangerousApi)

    override fun call(vararg args: Any?): Any? = delegate.call(*args)
}
