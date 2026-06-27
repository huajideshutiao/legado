package com.script.quickjs

import com.dokar.quickjs.QuickJs
import com.script.quickjs.QuickJsContext.Companion.threadLocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * QuickJS 执行上下文。
 *
 * 对应 Rhino 的 RhinoContext，承载协程上下文、安全名单标志、递归检查。
 * 通过 [threadLocalContext] 跟踪当前正在执行的 context，
 * 供 binding handler 和业务层 ([quickJsContext]) 访问。
 *
 * 线程模型: [QuickJsEngine.eval] 用 runBlocking(Dispatchers.Unconfined) 执行 evaluate,
 * 保证 evaluate 在当前线程执行，ThreadLocal 在 binding handler 中有效。
 *
 * 资源管理: 实现 [AutoCloseable]，close 时释放 native QuickJs 实例 +
 * 调 [JavaObjectBridge.releaseScope] / [JsFunctionHandle.releaseScope] 清理本 scope 注册的句柄,
 * 避免长期运行累积内存泄漏。
 */
class QuickJsContext(
    val quickJs: QuickJs,
    var coroutineContext: CoroutineContext? = null,
    var dangerousApi: Boolean = false,
    var allowScriptRun: Boolean = false,
    var recursiveCount: Int = 0
) : AutoCloseable {

    /**
     * 本 context 的唯一标识,用于 [JavaObjectBridge] / [JsFunctionHandle] 按范围释放句柄。
     */
    val scopeId: Long = scopeIdCounter.getAndIncrement()

    /**
     * 上次同步到 JS 全局变量 `__dangerousApi__` 的值。
     *
     * 用于 [QuickJsEngine.syncDangerousApiIfNeeded] 判断是否需要重新 evaluate:
     * 仅当 [dangerousApi] 与此值不同时才执行 `__dangerousApi__ = ...`,
     * 避免每次 eval 都重复同步相同值。
     *
     * 初始值 false,与 [JsBootstrap] 中 `var __dangerousApi__ = false;` 一致。
     * [QuickJsEngine.getRuntimeScope] 在初始化 bindings 后会更新此值。
     */
    @Volatile
    var lastSyncedDangerousApi: Boolean = false

    @Volatile
    private var closed: Boolean = false

    /**
     * 检查协程是否已取消，对应 RhinoContext.ensureActive()。
     * 在 binding handler 中由业务层 ([JsExtensions]) 调用。
     */
    @Throws(CancellationException::class)
    fun ensureActive() {
        coroutineContext?.ensureActive()
    }

    /**
     * 递归深度检查，防止 JS 递归调用导致栈溢出。
     */
    @Throws(ScriptException::class)
    fun checkRecursive() {
        if (recursiveCount >= MAX_RECURSION) {
            throw ScriptException("Maximum recursion depth ($MAX_RECURSION) exceeded")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // 释放本 scope 注册的 Java 对象/Class/Adapter 句柄
        JavaObjectBridge.releaseScope(scopeId)
        JsFunctionHandle.releaseScope(scopeId)
        // 关闭 native QuickJs 实例(释放 JS heap)
        quickJs.close()
    }

    companion object {
        const val MAX_RECURSION = 10

        /**
         * 当前线程正在执行的 QuickJsContext。
         * 在 [QuickJsEngine.eval] 进入 evaluate 前设置，evaluate 后清理。
         */
        val threadLocalContext = ThreadLocal<QuickJsContext>()

        private val scopeIdCounter = AtomicLong(1L)
    }
}
