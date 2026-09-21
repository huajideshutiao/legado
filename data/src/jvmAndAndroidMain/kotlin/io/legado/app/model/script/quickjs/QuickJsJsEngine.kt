package io.legado.app.model.script.quickjs

import com.script.quickjs.QuickJsContext
import com.script.quickjs.QuickJsEngine
import com.script.quickjs.ScriptBindings
import io.legado.app.model.script.JsBindings
import io.legado.app.model.script.JsCompiledScript
import io.legado.app.model.script.JsEngine
import io.legado.app.model.script.JsEngineType
import io.legado.app.model.script.JsObject
import io.legado.app.model.script.JsScope
import io.legado.app.model.script.quickjs.QuickJsJsEngine.toQuickJsBindings
import kotlin.coroutines.CoroutineContext

/**
 * quickjs 引擎实现：直接转发 [QuickJsEngine] 静态方法。
 *
 * # 下沉位置
 * 原在 `app/src/main/java/io/legado/app/model/script/quickjs/QuickJsJsEngine.kt`,
 * 桌面端 JS 引擎落地时下沉到 `modules/shared/src/jvmAndAndroidMain`,
 * 让 Android 与桌面 JVM 共用同一份引擎适配层 (复用 `modules:quickjs` KMP 化后的 commonMain API)。
 *
 * 包名 `io.legado.app.model.script.quickjs` 保持不变, app 端所有 import 无需修改,
 * 仅依赖 `modules:shared` 即可访问 (shared/jvmAndAndroidMain api 依赖 `:modules:quickjs`)。
 *
 * # 与 rhino 实现 的对应关系
 * 对应 rhino 的 `io.legado.app.model.script.rhino.RhinoJsEngine` (留 app, 不下沉)。
 * 业务层通过 [io.legado.app.model.script.JsEngines.get] 获取当前引擎,
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
     * 在共享 topScope 上执行包装后的 compiled, bindings 走 [QuickJsEngine.evalInSubScope]
     * 的子 scope 栈隔离 (对齐 rhino childScope.prototype=topScope)。
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

    // ============ 双判谓词（quickjs 具体类型自报）============

    override fun isJsObject(obj: Any?): Boolean = obj is com.script.quickjs.NativeObject

    override fun asJsObject(obj: Any?): JsObject? =
        (obj as? com.script.quickjs.NativeObject)?.let { QuickJsJsObject(it) }

    override fun isJsException(t: Throwable): Boolean = t is com.script.quickjs.ScriptException

    // ============ 当前线程 JS 执行上下文（从 app 顶层 JsScopeExtensions 搬入）============

    /**
     * 当前线程的 QuickJsContext（可空），包装为 [QuickJsJsScope]。
     *
     * 对应 app 顶层 `jsContextOrNull` 的 quickjs 分支。
     */
    override fun currentContext(): JsScope? =
        QuickJsContext.threadLocalContext.get()?.let { QuickJsJsScope(it) }

    /**
     * 当前线程的 QuickJsContext（非空），包装为 [QuickJsJsScope]。
     *
     * 对应 app 顶层 `jsContext` 的 quickjs 分支。
     *
     * @throws IllegalStateException 当前线程无 QuickJsContext 时
     */
    override fun currentContextNonNull(): JsScope {
        val ctx = QuickJsContext.threadLocalContext.get()
            ?: error("No QuickJsContext on current thread")
        return QuickJsJsScope(ctx)
    }

    /**
     * 在当前 QuickJsContext 上设 coroutineContext 后执行 block（不创建新 ctx）。
     *
     * 转发到 `com.script.quickjs.runScriptWithContext(context, block)`。
     */
    override fun <T> runScriptWithContext(context: CoroutineContext, block: () -> T): T =
        com.script.quickjs.runScriptWithContext(context, block)

    /**
     * suspend 版本，自动取当前协程上下文。
     *
     * 转发到 `com.script.quickjs.runScriptWithContext(block)`。
     */
    override suspend fun <T> runScriptWithContext(block: () -> T): T =
        com.script.quickjs.runScriptWithContext(block)

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
