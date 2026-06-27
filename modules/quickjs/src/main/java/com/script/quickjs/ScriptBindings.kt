package com.script.quickjs

/**
 * JS 作用域变量绑定容器。
 *
 * 不再继承 Rhino NativeObject,纯 Kotlin Map 实现。
 * 业务层通过 [buildScriptBindings] 构建,通过 operator set/get 设置变量。
 *
 * dangerousApi 控制是否旁路安全名单(由 BaseSource.enableDangerousApi 控制)。
 */
class ScriptBindings : MutableMap<String, Any?> by LinkedHashMap() {

    /**
     * 是否旁路安全名单。true 时 [JsSecurityPolicy] 全部放行。
     */
    var dangerousApi: Boolean = false

}
