package com.script.quickjs

/**
 * 构建 [ScriptBindings] 的便利函数。
 *
 * 业务层典型用法:
 * ```
 * val bindings = buildScriptBindings { bindings ->
 *     bindings["java"] = this
 *     bindings["result"] = result
 *     bindings.dangerousApi = source?.enableDangerousApi == true
 * }
 * ```
 */
fun buildScriptBindings(block: (bindings: ScriptBindings) -> Unit): ScriptBindings {
    val bindings = ScriptBindings()
    block(bindings)
    return bindings
}
