package io.legado.app.model.script.quickjs

import com.script.quickjs.CompiledScript
import com.script.quickjs.NativeObject
import com.script.quickjs.QuickJsContext
import io.legado.app.model.script.JsCompiledScript
import io.legado.app.model.script.JsObject
import io.legado.app.model.script.JsScope
import kotlin.coroutines.CoroutineContext

/**
 * quickjs scope 包装：直接委托 [QuickJsContext]。
 *
 * # 下沉位置
 * 与 [QuickJsJsEngine] 同批从 app 下沉到 `modules/shared/src/jvmAndAndroidMain`,
 * 让 Android 与桌面 JVM 共用。包名保持 `io.legado.app.model.script.quickjs` 不变,
 * app 端 `QuickJsSharedJsScopeProvider` 等仍可正常 import。
 *
 * 对应 rhino 的 `io.legado.app.model.script.rhino.RhinoJsScope` (留 app, 不下沉)。
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
 * 对应 rhino 的 `io.legado.app.model.script.rhino.RhinoJsCompiledScript`（bytecode 恒 null）。
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
