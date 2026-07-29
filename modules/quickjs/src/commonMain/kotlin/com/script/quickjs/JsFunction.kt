package com.script.quickjs

/**
 * JS function 的 Java 侧包装。
 *
 * 对应 rhino 的 `org.mozilla.javascript.Function`。
 * 用于需要把 JS function
 * 作为对象传递/调用的场景。
 *
 * 架构 A (自封装 native QuickJS):
 * - 持有 [context] (QuickJsContext), 通过 [QuickJsNative.nativeEval] 执行 JS
 * - 不再依赖 quickjs-kt 的 QuickJs (Phase 5 已移除)
 *
 * 线程模型: [call] 在调用方线程同步执行 nativeEval,
 * 并在调用前设置 [QuickJsContext.threadLocalContext],
 * 保证 binding handler 中 [quickJsContext] 可用。
 */
class JsFunction(
    val context: QuickJsContext,
    val functionExpr: String,
    val dangerousApi: Boolean = false
) {
    /**
     * native JSContext 指针 (取自 [context])。
     */
    val ctxPtr get() = context.ctxPtr

    /**
     * 调用 JS function。
     *
     * @param args 参数,基本类型直接拼字面量,Java 对象通过 __wrapJavaHandle(handle) 句柄包装
     * @return JS function 返回值
     *   - 基本类型 (String/Number/Boolean) 直接返回
     *   - Java 对象由 native 层 toJavaObject 解包为原始 Java 对象返回
     *   - null/undefined 返回 null
     */
    fun call(vararg args: Any?): Any? {
        val jsArgs = args.joinToString(",") { argToJsExpr(it) }
        val jsCode = "($functionExpr)($jsArgs)"
        return try {
            // 设置 ThreadLocalContext, 让 binding handler 能访问到当前 context
            val previous = QuickJsContext.threadLocalContext.get()
            QuickJsContext.threadLocalContext.set(context)
            try {
                // 同步 dangerousApi 到 native ctx opaque, 由 __getDangerousApi binding 从 opaque 读取,
                // 不再往 JS globalThis 挂 __dangerousApi__ (旧路径, bootstrap 已改走 binding)
                context.dangerousApi = dangerousApi
                if (context.lastSyncedDangerousApi != dangerousApi) {
                    QuickJsNative.nativeSetDangerousApi(ctxPtr, dangerousApi)
                    context.lastSyncedDangerousApi = dangerousApi
                }
                QuickJsNative.nativeEval(ctxPtr, jsCode)
            } finally {
                QuickJsContext.threadLocalContext.set(previous)
            }
        } catch (e: Throwable) {
            logQuickJsWarn(TAG, "call failed: $functionExpr", e)
            null
        }
    }

    /**
     * 把 Java 参数转为 JS 表达式字符串。
     *
     * - null/基本类型直接拼字面量
     * - Java 对象通过 [JavaObjectBridge.registerObject] 注册句柄,
     *   用 __wrapJavaHandle(handle) 包装为 JS JavaObject (走 native trap)
     */
    private fun argToJsExpr(arg: Any?): String {
        if (arg == null) return "null"
        if (arg is String) return JsStringUtils.escape(arg)
        if (arg is Number) return arg.toString()
        if (arg is Boolean) return arg.toString()
        if (arg is Char) return JsStringUtils.escape(arg.toString())
        // Java 对象通过句柄包装
        if (!JsSecurityPolicy.isObjectVisible(arg, dangerousApi)) return "null"
        val handle = JavaObjectBridge.registerObject(arg)
        // __wrapJavaHandle 是 native binding, 返回 JavaObject
        // 注意: JS 数字字面量不能有 L 后缀(那是 Java/Kotlin Long 语法),会被 JS 解析为 SyntaxError
        return "__wrapJavaHandle($handle)"
    }

    companion object {
        private const val TAG = "JsFunction"
    }
}
