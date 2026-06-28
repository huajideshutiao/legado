package com.script.quickjs

import com.script.quickjs.QuickJsContext.Companion.findByCtxPtr
import com.script.quickjs.QuickJsContext.Companion.threadLocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * QuickJS 执行上下文。
 *
 * 对应 Rhino 的 RhinoContext，承载 native ctx 指针、协程上下文、安全名单标志、递归检查。
 * 通过 [threadLocalContext] 跟踪当前正在执行的 context，
 * 供 binding handler ([BindingHandler]) 和业务层 ([quickJsContext]) 访问。
 *
 * 架构 A (自封装 native QuickJS):
 * - 持有 [rtPtr] (native JSRuntime 指针) 和 [ctxPtr] (native JSContext 指针)
 * - 不再依赖 quickjs-kt 的 QuickJs (Phase 5 已移除)
 * - 通过 [QuickJsNative] 调用 native API
 *
 * 线程模型: JS 执行线程通过 [QuickJsEngine.eval] 进入,
 * ThreadLocal 在 binding handler 中有效。
 *
 * 资源管理: 实现 [AutoCloseable]，close 时释放 native JSContext/JSRuntime +
 * 调 [JavaObjectBridge.releaseScope] / [JsFunctionHandle.releaseScope] 清理本 scope 注册的句柄,
 * 避免长期运行累积内存泄漏。
 *
 * ctxPtr 全局查找: [findByCtxPtr] 供 JavaAdapter 回调时由 [JsFunctionHandle] 反向
 * 查找 ctxPtr 对应的 QuickJsContext (用于设置 ThreadLocal)。
 */
class QuickJsContext(
    /** native JSRuntime 指针, close 时释放 */
    val rtPtr: Long,
    /** native JSContext 指针, 所有 JS 操作通过此句柄调用 QuickJsNative */
    val ctxPtr: Long,
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
     * 上次同步到 native ctx opaque 的 dangerousApi 值。
     *
     * 用于 [QuickJsEngine.syncDangerousApiIfNeeded] 判断是否需要重新调用 nativeSetDangerousApi:
     * 仅当 [dangerousApi] 与此值不同时才同步, 避免每次 eval 都重复 native 调用。
     *
     * 初始值 false, 与 [JsBootstrap] 中 `var __dangerousApi__ = false;` 一致。
     * [QuickJsEngine.getRuntimeScope] 在初始化 bindings 后会更新此值。
     */
    @Volatile
    var lastSyncedDangerousApi: Boolean = false

    /**
     * Java 对象身份 → 句柄复用映射 (优化 3.2)。
     *
     * 同一 Java 对象多次跨 bridge 返回 JS 时,如果每次都 registerObject 新句柄,
     * 会让 objectMap 不必要膨胀 (handle 累积只能等 [close] 释放),
     * JS 侧也拿到不同 JavaObject (身份不一致)。
     *
     * 用 [IdentityHashMap] 按引用相等 (== 而非 equals) 去重:
     * 避免"内容相等但身份不同"(如两个 "abc" 字符串)被错误地共享同一 handle。
     *
     * 仅在 [threadLocalContext] 设置时由 [JavaObjectBridge.getOrRegisterIdentityHandle] 使用。
     * 线程模型: ctx 单线程顺序访问 (eval 与 binding handler 同线程),
     * 不需要同步; 跨线程复用 ctx 时由调用方保证串行。
     *
     * 生命周期: 与 ctx 同生共死, [close] 时显式 clear 让强引用尽早释放;
     * 句柄本体由 [JavaObjectBridge.releaseScope] 统一释放。
     */
    val identityHandles: IdentityHashMap<Any, Long> = IdentityHashMap()

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
        // 清空身份去重映射, 让强引用尽早断开 (句柄本体由 releaseScope 统一释放)
        identityHandles.clear()
        // 释放本 scope 注册的 Java 对象/Class/Adapter 句柄
        JavaObjectBridge.releaseScope(scopeId)
        JsFunctionHandle.releaseScope(scopeId)
        // 从全局 ctxPtr 注册表移除
        unregisterCtxPtr()
        // 释放 native JSContext / JSRuntime (顺序: 先 ctx 后 rt)
        if (ctxPtr != 0L) {
            QuickJsNative.nativeFreeContext(ctxPtr)
        }
        if (rtPtr != 0L) {
            QuickJsNative.nativeFreeRuntime(rtPtr)
        }
    }

    // ============ ctxPtr 全局注册表 (供 JavaAdapter 回调反向查找) ============

    /**
     * 把 (ctxPtr -> this) 注册到全局表。
     * 在 [QuickJsEngine.getRuntimeScope] 创建 ctx 后调用。
     */
    init {
        registerCtxPtr()
    }

    private fun registerCtxPtr() {
        synchronized(ctxPtrRegistry) {
            ctxPtrRegistry[ctxPtr] = this
        }
    }

    private fun unregisterCtxPtr() {
        synchronized(ctxPtrRegistry) {
            ctxPtrRegistry.remove(ctxPtr)
        }
    }

    companion object {
        const val MAX_RECURSION = 10

        /**
         * 当前线程正在执行的 QuickJsContext。
         * 在 [QuickJsEngine.eval] 进入 evaluate 前设置，evaluate 后清理。
         */
        val threadLocalContext = ThreadLocal<QuickJsContext>()

        private val scopeIdCounter = AtomicLong(1L)

        /**
         * ctxPtr -> QuickJsContext 全局注册表。
         * 用于 JavaAdapter 回调时由 [JsFunctionHandle] 反向查找 ctxPtr 对应的 ctx,
         * 以便设置 [threadLocalContext] (供 binding handler 使用)。
         *
         * 线程安全: 通过 synchronized 保护, 注册/注销/查找都同步。
         * 数量通常很少 (活跃 scope 数), 遍历查找开销可忽略。
         */
        private val ctxPtrRegistry = java.util.concurrent.ConcurrentHashMap<Long, QuickJsContext>()

        /**
         * 按 ctxPtr 查找 QuickJsContext。
         * 用于 [JsFunctionHandle.invokeJsMethod] 设置 ThreadLocal。
         */
        @JvmStatic
        fun findByCtxPtr(ctxPtr: Long): QuickJsContext? {
            return ctxPtrRegistry[ctxPtr]
        }
    }
}
