package com.script.rhino

import com.script.ScriptException
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.RhinoException

class RhinoInterruptError(override val cause: Throwable) : Error()

class RhinoRecursionError : Error("Maximum recursion depth exceeded.")

internal fun RhinoException.toScriptException(): ScriptException {
    val line = if (lineNumber() == 0) -1 else lineNumber()
    val msg = if (this is JavaScriptException) value.toString() else toString()
    return ScriptException(msg, sourceName(), line).also { it.initCause(this) }
}
