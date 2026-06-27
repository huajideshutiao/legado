package com.script.quickjs

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * JS function 的 Java 侧包装。
 *
 * 对应 rhino 的 `org.mozilla.javascript.Function`。
 * 用于 [io.legado.app.ui.association.JsActivity] 等需要把 JS function
 * 作为对象传递/调用的场景。
 *
 * 持有 [QuickJsContext] 和 JS function 表达式(全局变量名),调用 [call] 时
 * 在对应实例上 evaluate 执行。
 *
 * 线程模型: [call] 用 [runBlocking](Dispatchers.Unconfined) 桥接 suspend evaluate,
 * 与 [QuickJsEngine.eval] 一致,并在 evaluate 前设置 [QuickJsContext.threadLocalContext],
 * 保证 binding handler 中 [quickJsContext] 可用。
 */
class JsFunction(
    val context: QuickJsContext,
    val functionExpr: String,
    val dangerousApi: Boolean = false
) {

    /**
     * QuickJs 实例(取自 [context])。
     */
    val quickJs get() = context.quickJs

    /**
     * 调用 JS function。
     *
     * @param args 参数,基本类型直接拼字面量,Java 对象通过句柄包装
     * @return JS function 返回值(基本类型直接返回,Java 对象通过句柄包装为 Map)
     */
    fun call(vararg args: Any?): Any? {
        val jsArgs = args.joinToString(",") { argToJsExpr(it) }
        return try {
            runBlocking(Dispatchers.Unconfined) {
                // 设置 ThreadLocalContext,让 binding handler 能访问到当前 context
                val previous = QuickJsContext.threadLocalContext.get()
                QuickJsContext.threadLocalContext.set(context)
                try {
                    // 同步 dangerousApi 全局变量(与 QuickJsEngine.eval 一致,仅变化时同步)
                    context.dangerousApi = dangerousApi
                    if (context.lastSyncedDangerousApi != dangerousApi) {
                        quickJs.evaluate<Any?>("__dangerousApi__ = $dangerousApi;")
                        context.lastSyncedDangerousApi = dangerousApi
                    }
                    quickJs.evaluate<Any?>("($functionExpr)($jsArgs)")
                } finally {
                    QuickJsContext.threadLocalContext.set(previous)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "call failed: $functionExpr", e)
            null
        }
    }

    private fun argToJsExpr(arg: Any?): String {
        if (arg == null) return "null"
        if (arg is String) return JsStringUtils.escape(arg)
        if (arg is Number) return arg.toString()
        if (arg is Boolean) return arg.toString()
        if (arg is Char) return JsStringUtils.escape(arg.toString())
        // Java 对象通过句柄包装
        if (!JsSecurityPolicy.isObjectVisible(arg, dangerousApi)) return "null"
        val handle = JavaObjectBridge.registerObject(arg)
        return "__wrapJavaObject($handle)"
    }

    companion object {
        private const val TAG = "JsFunction"
    }
}
