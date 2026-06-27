package com.script.quickjs

import kotlin.coroutines.CoroutineContext

/**
 * 编译后的 JS 脚本(bytecode 包装)。
 *
 * 对应 Rhino 的 CompiledScript,内部持有 QuickJS bytecode (ByteArray)。
 * 通过 [QuickJsEngine.compile] 生成,用 [eval] 执行。
 *
 * 编译后的 bytecode 可缓存复用,避免重复解析 JS 源码。
 * bytecode 跨 QuickJs 实例兼容(QuickJS bytecode 格式标准),
 * 可在任意 [QuickJsContext] 上执行。
 */
class CompiledScript(bytecode: ByteArray) {

    /**
     * bytecode 防御性拷贝,避免外部修改影响后续执行。
     *
     * 调用方(如 AnalyzeRule.compileScriptCache)可能持有同一引用,
     * 拷贝确保 CompiledScript 内部状态不可变。
     */
    val bytecode: ByteArray = bytecode.copyOf()

    /**
     * 在指定 scope 上执行 bytecode。
     *
     * 对应 Rhino 的 CompiledScript.eval(scope, coroutineContext)。
     */
    fun eval(scope: QuickJsContext, coroutineContext: CoroutineContext?): Any? {
        return QuickJsEngine.evalBytecode(bytecode, scope, coroutineContext)
    }
}
