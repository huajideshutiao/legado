package io.legado.app.model.script

import kotlin.coroutines.CoroutineContext

/**
 * 用户脚本求值入口（规则解析 / URL 解析 / 书源自身三处 evalJS 共用）。
 *
 * bindings 注入子作用域后由引擎固定执行器求值，执行完清理，不跨调用残留。
 *
 * @param sharedScope 可复用的共享作用域；为 null 时临时建一个并在结束时关闭
 */
fun evalUserScript(
    jsStr: String,
    sharedScope: JsScope?,
    bindings: JsBindings,
    coroutineContext: CoroutineContext?
): Any? {
    val engine = JsEngines.get()
    if (sharedScope != null) {
        return engine.evalInSubScope(jsStr, sharedScope, bindings, coroutineContext)
    }
    val scope = engine.getRuntimeScope(JsBindings())
    return try {
        engine.evalInSubScope(jsStr, scope, bindings, coroutineContext)
    } finally {
        scope.close()
    }
}

/**
 * 正则替换的 JS 求值器（Android / Desktop / iOS+鸿蒙三端正则替换共用）。
 *
 * 循环外建共享作用域，逐匹配注入 `result` 求值；[close] 释放作用域。
 */
class JsReplacementEvaluator(private val js: String) : AutoCloseable {

    private val scope: JsScope = JsEngines.get().getRuntimeScope(JsBindings())

    /** 对单个匹配文本求值，返回替换文本。 */
    fun evaluate(matchValue: String): String {
        val bindings = JsBindings().apply {
            this["result"] = matchValue
            dangerousApi = false
        }
        return JsEngines.get().evalInSubScope(js, scope, bindings, null)?.toString() ?: ""
    }

    override fun close() {
        scope.close()
    }
}
