/*
 * Copyright (c) 2005, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.script.rhino

import com.script.AbstractScriptEngine
import com.script.Bindings
import com.script.Compilable
import com.script.CompiledScript
import com.script.ScriptBindings
import com.script.ScriptContext
import com.script.ScriptException
import com.script.SimpleBindings
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Callable
import org.mozilla.javascript.ConsString
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.ContinuationPending
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import kotlin.coroutines.CoroutineContext

/**
 * Implementation of `ScriptEngine` using the Mozilla Rhino interpreter.
 *
 * @author Mike Grogan
 * @author A. Sundararajan
 * @since 1.6
 */
@Suppress("MemberVisibilityCanBePrivate")
object RhinoScriptEngine : AbstractScriptEngine(), Compilable {
    private val topLevel: RhinoTopLevel
    private val indexedProps: MutableMap<Any, Any?> = HashMap()

    fun eval(js: String, bindingsConfig: ScriptBindings.() -> Unit = {}): Any? {
        // 空字符串早返回，避免不必要的编译执行开销
        if (js.isBlank()) return null
        val bindings = ScriptBindings().apply(bindingsConfig)
        return eval(js, bindings)
    }

    override fun eval(
        reader: Reader,
        scope: Scriptable,
        coroutineContext: CoroutineContext?
    ): Any? {
        val cx = Context.enter() as RhinoContext
        val previousCoroutineContext = cx.coroutineContext
        val previousDangerousApi = cx.dangerousApi
        val previousAllowScriptRun = cx.allowScriptRun
        if (coroutineContext != null && coroutineContext[Job] != null) {
            cx.coroutineContext = coroutineContext
        }
        if (scope is ScriptBindings) cx.dangerousApi = scope.dangerousApi
        cx.allowScriptRun = true
        cx.recursiveCount++
        val ret: Any?
        try {
            cx.checkRecursive()
            val filename = this["javax.script.filename"] as? String ?: "<Unknown source>"
            ret = cx.evaluateReader(scope, reader, filename, 1, null)
        } catch (re: RhinoException) {
            throw re.toScriptException()
        } catch (e: IOException) {
            throw ScriptException(e)
        } finally {
            cx.coroutineContext = previousCoroutineContext
            cx.dangerousApi = previousDangerousApi
            cx.allowScriptRun = previousAllowScriptRun
            cx.recursiveCount--
            Context.exit()
        }
        return unwrapReturnValue(ret)
    }

    @Throws(ContinuationPending::class)
    override suspend fun evalSuspend(reader: Reader, scope: Scriptable): Any? {
        val cx = Context.enter() as RhinoContext
        val previousDangerousApi = cx.dangerousApi
        val previousAllowScriptRun = cx.allowScriptRun
        if (scope is ScriptBindings) cx.dangerousApi = scope.dangerousApi
        var ret: Any?
        withContext(RhinoContext.threadLocalContext.asContextElement(cx)) {
            cx.allowScriptRun = true
            cx.recursiveCount++
            try {
                cx.checkRecursive()
                val filename = this@RhinoScriptEngine["javax.script.filename"] as? String
                    ?: "<Unknown source>"
                val script = cx.compileReader(reader, filename, 1, null)
                try {
                    ret = cx.executeScriptWithContinuations(script, scope)
                } catch (e: ContinuationPending) {
                    var pending = e
                    while (true) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val suspendFunction = pending.applicationState as suspend () -> Any?
                            val functionResult = suspendFunction()
                            ret = cx.resumeContinuation(pending.continuation, scope, functionResult)
                            break
                        } catch (e2: ContinuationPending) {
                            pending = e2
                        }
                    }
                }
            } catch (re: RhinoException) {
                throw re.toScriptException()
            } catch (e: IOException) {
                throw ScriptException(e)
            } finally {
                cx.dangerousApi = previousDangerousApi
                cx.allowScriptRun = previousAllowScriptRun
                cx.recursiveCount--
                Context.exit()
            }
        }
        return unwrapReturnValue(ret)
    }

    override fun createBindings(): Bindings {
        return SimpleBindings()
    }

    override fun getRuntimeScope(context: ScriptContext): Scriptable {
        val newScope: Scriptable = ExternalScriptable(context, indexedProps)
        val cx = Context.enter()
        try {
            newScope.prototype = RhinoTopLevel(cx, this)
        } finally {
            Context.exit()
        }
        return newScope
    }

    override fun getRuntimeScope(bindings: ScriptBindings): Scriptable {
        val cx = Context.enter()
        try {
            bindings.prototype = cx.initStandardObjects()
        } finally {
            Context.exit()
        }
        return bindings
    }

    @Throws(ScriptException::class)
    override fun compile(script: String): CompiledScript {
        return this.compile(StringReader(script) as Reader)
    }

    @Throws(ScriptException::class)
    override fun compile(script: Reader): CompiledScript {
        val cx = Context.enter()
        try {
            val fileName = this["javax.script.filename"] as? String ?: "<Unknown Source>"
            val scr = cx.compileReader(script, fileName, 1, null)
            return RhinoCompiledScript(this, scr)
        } catch (e: Exception) {
            throw ScriptException(e)
        } finally {
            Context.exit()
        }
    }

    fun unwrapReturnValue(result: Any?): Any? {
        var v = result
        if (v is Wrapper) v = v.unwrap()
        if (v is ConsString) v = v.toString()
        return if (v is Undefined) null else v
    }

    init {
        ContextFactory.initGlobal(object : ContextFactory() {

            override fun makeContext(): Context {
                val cx = RhinoContext(this)
                cx.languageVersion = Context.VERSION_ES6
                cx.isInterpretedMode = true
                cx.setClassShutter(RhinoClassShutter)
                cx.wrapFactory = RhinoWrapFactory
                cx.instructionObserverThreshold = 10000
                cx.maximumInterpreterStackDepth = 1000
                return cx
            }

            override fun hasFeature(cx: Context, featureIndex: Int): Boolean {
                return when (featureIndex) {
                    Context.FEATURE_ENABLE_JAVA_MAP_ACCESS -> true
                    else -> super.hasFeature(cx, featureIndex)
                }
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (cx is RhinoContext) {
                    cx.ensureActive()
                }
            }

            override fun doTopCall(
                callable: Callable,
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable?,
                args: Array<Any>
            ): Any? {
                try {
                    if (cx is RhinoContext) {
                        if (!cx.allowScriptRun) {
                            error("Not allow run script in unauthorized way.")
                        }
                        cx.ensureActive()
                    }
                    return super.doTopCall(callable, cx, scope, thisObj, args)
                } catch (e: RhinoInterruptError) {
                    throw e.cause
                }
            }
        })

        val cx = Context.enter()
        try {
            topLevel = RhinoTopLevel(cx, this)
        } finally {
            Context.exit()
        }
    }
}
