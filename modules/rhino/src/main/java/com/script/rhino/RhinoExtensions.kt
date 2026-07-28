package com.script.rhino

import kotlinx.coroutines.currentCoroutineContext
import org.mozilla.javascript.Context
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

val rhinoContext: RhinoContext
    get() = Context.getCurrentContext() as RhinoContext

val rhinoContextOrNull: RhinoContext?
    get() = Context.getCurrentContext() as? RhinoContext

inline fun <T> runScriptWithContext(context: CoroutineContext, block: () -> T): T {
    RhinoScriptEngine
    val rhinoContext = Context.enter() as RhinoContext
    val previousCoroutineContext = rhinoContext.coroutineContext
    rhinoContext.coroutineContext = context.minusKey(ContinuationInterceptor)
    try {
        return block()
    } finally {
        rhinoContext.coroutineContext = previousCoroutineContext
        Context.exit()
    }
}

suspend inline fun <T> runScriptWithContext(block: () -> T): T {
    val rhinoContext = Context.enter() as RhinoContext
    val previousCoroutineContext = rhinoContext.coroutineContext
    rhinoContext.coroutineContext = currentCoroutineContext().minusKey(ContinuationInterceptor)
    try {
        return block()
    } finally {
        rhinoContext.coroutineContext = previousCoroutineContext
        Context.exit()
    }
}
