package io.legado.app.model.script

import kotlin.coroutines.CoroutineContext

/**
 * app 侧统一顶层扩展，按 [JsEngines.type] 分派到 rhino/quickjs 各自实现。
 *
 * 业务代码 import 本包，不再直接 import `com.script.quickjs.*` 或 `com.script.rhino.*`。
 *
 * - [jsContext] / [jsContextOrNull]: 取当前线程的 JS 执行上下文（rhino Context / quickjs QuickJsContext）
 * - [runScriptWithContext]: 在 JS 执行上下文中运行 block
 *
 * 注意：rhino 与 quickjs 的 [runScriptWithContext] 语义不同：
 * - rhino: `Context.enter()` 创建新 ctx + 设 coroutineContext + block + `Context.exit()`
 * - quickjs: 在现有 ThreadLocal ctx 上设 coroutineContext（不创建新 ctx）
 *
 * app 侧按引擎分派到各模块 inline 版本，不抽象统一（语义差异大，强行抽象会引入 bug）。
 */

/**
 * 当前线程的 JS 执行上下文。
 *
 * - rhino: 取 `Context.getCurrentContext()`，包装为 [io.legado.app.model.script.rhino.RhinoJsScope]
 *   （scriptable=null，仅访问 ctx 属性如 coroutineContext/ensureActive）
 * - quickjs: 取 `QuickJsContext.threadLocalContext`，包装为 [io.legado.app.model.script.quickjs.QuickJsJsScope]
 *
 * @throws IllegalStateException 当前线程无 JS 执行上下文时
 */
val jsContext: JsScope
    get() = when (JsEngines.type) {
        JsEngineType.RHINO -> {
            // rhino 的 Context 是 enter/exit 栈管理，getCurrentContext 返回当前线程栈顶 Context
            val cx = org.mozilla.javascript.Context.getCurrentContext()
                ?: error("No rhino Context on current thread")
            // RhinoJsScope.scriptable=null：仅用于访问 ctx 属性（coroutineContext/ensureActive 等），
            // 不参与 eval（eval 需要 Scriptable，从 getRuntimeScope/SharedJsScope 来时非 null）
            io.legado.app.model.script.rhino.RhinoJsScope()
        }

        JsEngineType.QUICKJS -> {
            val ctx = com.script.quickjs.QuickJsContext.threadLocalContext.get()
                ?: error("No QuickJsContext on current thread")
            io.legado.app.model.script.quickjs.QuickJsJsScope(ctx)
        }
    }

/**
 * 当前线程的 JS 执行上下文，无上下文时返回 null（不抛异常）。
 *
 * 用于非 JS 执行路径的防御性访问（如 JsExtensions 中部分方法可能在非 JS 线程调用）。
 */
val jsContextOrNull: JsScope?
    get() = when (JsEngines.type) {
        JsEngineType.RHINO -> {
            // 仅当当前线程有 RhinoContext 时返回 scope（cast 失败说明非 rhino JS 执行线程）
            (org.mozilla.javascript.Context.getCurrentContext() as? com.script.rhino.RhinoContext)
                ?.let { io.legado.app.model.script.rhino.RhinoJsScope() }
        }

        JsEngineType.QUICKJS -> {
            com.script.quickjs.QuickJsContext.threadLocalContext.get()
                ?.let { io.legado.app.model.script.quickjs.QuickJsJsScope(it) }
        }
    }

/**
 * 在 JS 执行上下文中运行 block。
 *
 * rhino: `Context.enter()` 创建新 ctx + 设 coroutineContext + block + `Context.exit()`。
 * quickjs: 在现有 ThreadLocal ctx 上设 coroutineContext（不创建新 ctx）。
 *
 * 对应 quickjs 的 `com.script.quickjs.runScriptWithContext` 和 rhino 的 `com.script.rhino.runScriptWithContext`。
 */
inline fun <T> runScriptWithContext(context: CoroutineContext, block: () -> T): T =
    when (JsEngines.type) {
        JsEngineType.RHINO -> com.script.rhino.runScriptWithContext(context, block)
        JsEngineType.QUICKJS -> com.script.quickjs.runScriptWithContext(context, block)
    }

/**
 * suspend 版本，取当前协程上下文。
 *
 * rhino: `Context.enter()` + `currentCoroutineContext()` + block + `Context.exit()`。
 * quickjs: 在现有 ThreadLocal ctx 上设 `currentCoroutineContext()`。
 */
suspend inline fun <T> runScriptWithContext(crossinline block: () -> T): T =
    when (JsEngines.type) {
        JsEngineType.RHINO -> com.script.rhino.runScriptWithContext { block() }
        JsEngineType.QUICKJS -> com.script.quickjs.runScriptWithContext { block() }
    }
