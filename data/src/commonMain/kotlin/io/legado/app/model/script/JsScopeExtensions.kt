package io.legado.app.model.script

import kotlin.coroutines.CoroutineContext

/**
 * app 侧统一顶层扩展薄壳，转发到 [JsEngines]（具体实现下沉到各引擎 [JsEngine] 子类）。
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
 * 具体实现见 [QuickJsJsEngine](../quickjs/QuickJsJsEngine.kt) /
 * [RhinoJsEngine](../rhino/RhinoJsEngine.kt) 的 `currentContext` / `currentContextNonNull` /
 * `runScriptWithContext` 方法。
 *
 * 保留顶层薄壳是为了消费方 import 路径不变（JsExtensions 等仍 import
 * `io.legado.app.model.script.jsContext`），改动最小。
 */

/**
 * 当前线程的 JS 执行上下文（非空）。
 *
 * 转发到 [JsEngines.jsContext]（最终调用 [JsEngine.currentContextNonNull]）。
 *
 * @throws IllegalStateException 当前线程无 JS 执行上下文时
 */
val jsContext: JsScope
    get() = JsEngines.jsContext

/**
 * 当前线程的 JS 执行上下文（可空）。
 *
 * 转发到 [JsEngines.jsContextOrNull]（最终调用 [JsEngine.currentContext]）。
 */
val jsContextOrNull: JsScope?
    get() = JsEngines.jsContextOrNull

/**
 * 在 JS 执行上下文中运行 block（同步版，传入外层 [CoroutineContext]）。
 *
 * 转发到 [JsEngines.runScriptWithContext]。
 *
 * 注意：原顶层 API 为 `inline fun`，下沉薄壳后 [block] 需转发给非 inline 接口方法
 * [JsEngines.runScriptWithContext]，无法内联，故去掉 `inline` 标记（行为等价，
 * 消费方的 lambda 无非局部返回依赖）。
 */
fun <T> runScriptWithContext(context: CoroutineContext, block: () -> T): T =
    JsEngines.runScriptWithContext(context, block)

/**
 * 在 JS 执行上下文中运行 block（suspend 版，自动取当前协程上下文）。
 *
 * 转发到 [JsEngines.runScriptWithContext]。
 *
 * 注意：去掉 `inline` 标记的原因同上。
 */
suspend fun <T> runScriptWithContext(block: () -> T): T =
    JsEngines.runScriptWithContext(block)
