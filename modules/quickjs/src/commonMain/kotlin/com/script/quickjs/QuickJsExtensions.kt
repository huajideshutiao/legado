package com.script.quickjs

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * 当前线程正在执行的 QuickJsContext。
 * 在 [QuickJsEngine.eval] 进入 evaluate 前通过 ThreadLocal 设置。
 * 对应 Rhino 的 rhinoContext。
 */
val quickJsContext: QuickJsContext
    get() = QuickJsContext.threadLocalContext.get()
        ?: error("No QuickJs context in current thread")

/**
 * 当前线程正在执行的 QuickJsContext，可能为 null。
 * 对应 Rhino 的 rhinoContextOrNull。
 */
val quickJsContextOrNull: QuickJsContext?
    get() = QuickJsContext.threadLocalContext.get()

/**
 * 在当前 QuickJsContext 上设置协程上下文后执行 block。
 * 用于将外层协程的取消能力传递到 JS 执行期间。
 *
 * 对应 Rhino 的 runScriptWithContext(context, block)。
 */
inline fun <T> runScriptWithContext(context: CoroutineContext, block: () -> T): T {
    val ctx = quickJsContextOrNull
    val previous = ctx?.coroutineContext
    ctx?.coroutineContext = context.minusKey(ContinuationInterceptor)
    try {
        return block()
    } finally {
        ctx?.coroutineContext = previous
    }
}

/**
 * suspend 版本，自动取当前协程上下文。
 *
 * 对应 Rhino 的 runScriptWithContext(block)。
 */
suspend inline fun <T> runScriptWithContext(block: () -> T): T {
    val ctx = quickJsContextOrNull
    val previous = ctx?.coroutineContext
    ctx?.coroutineContext = currentCoroutineContext().minusKey(ContinuationInterceptor)
    try {
        return block()
    } finally {
        ctx?.coroutineContext = previous
    }
}
