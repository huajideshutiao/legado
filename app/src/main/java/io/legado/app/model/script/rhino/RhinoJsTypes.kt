package io.legado.app.model.script.rhino

import com.script.rhino.RhinoContext
import io.legado.app.model.script.JsCompiledScript
import io.legado.app.model.script.JsFn
import io.legado.app.model.script.JsObject
import io.legado.app.model.script.JsScope
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import kotlin.coroutines.CoroutineContext

/**
 * rhino scope 包装。
 *
 * [scriptable] 可空：从 [io.legado.app.model.script.jsContext] 取当前线程 ctx 时为 null
 * （仅用于访问 coroutineContext/ensureActive 等 ctx 属性，不参与 eval）；
 * 从 [RhinoJsEngine.getRuntimeScope] / SharedJsScope 来时非 null（参与 eval/evalInSubScope）。
 *
 * coroutineContext/allowScriptRun/recursiveCount 通过 `Context.getCurrentContext() as? RhinoContext`
 * 动态访问（rhino 的 Context 是 enter/exit 栈管理，非 ThreadLocal 单值）。
 *
 * close() 空实现：rhino scope 由 GC + sealObject 管理，无显式 close。
 */
class RhinoJsScope(
    val scriptable: Scriptable? = null
) : JsScope {

    override var coroutineContext: CoroutineContext?
        get() = (Context.getCurrentContext() as? RhinoContext)?.coroutineContext
        set(value) {
            (Context.getCurrentContext() as? RhinoContext)?.coroutineContext = value
        }

    override var allowScriptRun: Boolean
        get() = (Context.getCurrentContext() as? RhinoContext)?.allowScriptRun ?: false
        set(value) {
            (Context.getCurrentContext() as? RhinoContext)?.allowScriptRun = value
        }

    override var recursiveCount: Int
        get() = (Context.getCurrentContext() as? RhinoContext)?.recursiveCount ?: 0
        set(value) {
            (Context.getCurrentContext() as? RhinoContext)?.recursiveCount = value
        }

    override fun ensureActive() {
        (Context.getCurrentContext() as? RhinoContext)?.ensureActive()
    }

    override fun checkRecursive() {
        (Context.getCurrentContext() as? RhinoContext)?.checkRecursive()
    }

    override fun close() {
        // rhino scope 由 GC + sealObject 管理，无显式 close
    }
}

/**
 * rhino 编译脚本包装：委托 `com.script.CompiledScript`。
 *
 * [bytecode] 恒为 null（rhino 无 bytecode 概念）。
 */
class RhinoJsCompiledScript(
    val delegate: com.script.CompiledScript
) : JsCompiledScript {

    override val bytecode: ByteArray? = null

    override fun eval(scope: JsScope, coroutineContext: CoroutineContext?): Any? {
        val rhinoScope = scope as? RhinoJsScope
            ?: error("RhinoJsCompiledScript requires RhinoJsScope, got ${scope::class}")
        val scriptable = rhinoScope.scriptable
            ?: error("RhinoJsScope.scriptable is null, cannot eval on a context-only scope")
        return delegate.eval(scriptable, coroutineContext)
    }
}

/**
 * rhino NativeObject 的 JsObject 适配。
 *
 * `org.mozilla.javascript.NativeObject` 不直接实现 java.util.Map，
 * 这里通过 Scriptable.get/put/has/delete/ids 实现 MutableMap 接口，
 * 让业务层 `jsObj[rule]` 走 Map 索引（与 quickjs NativeObject 行为一致）。
 */
class RhinoJsObject(private val delegate: NativeObject) : AbstractMutableMap<String, Any?>(),
    JsObject {

    override val size: Int
        get() = delegate.ids.size

    override fun isEmpty(): Boolean = delegate.ids.isEmpty()

    override fun containsKey(key: String): Boolean =
        delegate.has(key, delegate)

    override fun get(key: String): Any? =
        delegate.get(key, delegate)

    override fun put(key: String, value: Any?): Any? {
        val previous = delegate.get(key, delegate)
        delegate.put(key, delegate, value)
        return previous
    }

    override fun remove(key: String): Any? {
        val previous = delegate.get(key, delegate)
        delegate.delete(key)
        return previous
    }

    override fun clear() {
        delegate.ids.forEach { delegate.delete(it.toString()) }
    }

    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
        get() = delegate.ids.map { rawKey ->
            val k = rawKey.toString()
            object : MutableMap.MutableEntry<String, Any?> {
                override val key: String = k
                override val value: Any?
                    get() = delegate.get(k, delegate)

                override fun setValue(newValue: Any?): Any? {
                    val old = delegate.get(k, delegate)
                    delegate.put(k, delegate, newValue)
                    return old
                }
            }
        }.toMutableSet()
}

/**
 * rhino JsFn 实现：持 scope + functionExpr，call 时创建子 scope 注入 args 变量后 eval。
 *
 * 对应 quickjs 的 `com.script.quickjs.JsFunction`。
 * args 通过子 scope 变量注入（`__arg0`, `__arg1`, ...），JS 端用 `(functionExpr)(__arg0, __arg1)` 调用。
 */
class RhinoJsFn(
    private val scope: RhinoJsScope,
    private val functionExpr: String,
    override val dangerousApi: Boolean = false
) : JsFn {

    override fun call(vararg args: Any?): Any? {
        val scriptable = scope.scriptable ?: return null
        val childScope = com.script.ScriptBindings().apply {
            this.dangerousApi = dangerousApi
            prototype = scriptable
            args.forEachIndexed { i, arg -> this["__arg$i"] = arg }
        }
        val jsArgs = args.indices.joinToString(",") { "__arg$it" }
        return com.script.rhino.RhinoScriptEngine.eval("($functionExpr)($jsArgs)", childScope, null)
    }
}
