package com.script.quickjs

import com.script.quickjs.QuickJsContext.Companion.findByCtxPtr
import com.script.quickjs.QuickJsContext.Companion.threadLocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
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
 * 资源管理 (双路径):
 * - 显式 [close]: 立即同步释放 native JSContext/JSRuntime +
 *   [JavaObjectBridge.releaseScope] / [JsFunctionHandle.releaseScope] 清本 scope 注册的句柄。
 *   适合一次性 scope (如 [QuickJsEngine.eval] 内部) 和测试。
 * - GC 兜底: 通过 [NativeCleanup] (PhantomReference + 守护线程) 在本对象被 GC 回收时
 *   异步释放 native 资源。对齐 rhino 版 SharedJsScope 的语义 — 共享 scope 用 LRU 弱引用
 *   缓存, 业务层强引用决定生命周期, 没人用时 GC 触发 cleanup。避免"LRU 淘汰时同步 close
 *   与另一线程正在 evalJS 的 ctx 形成 use-after-free"。
 *
 * close 必须 idempotent: 显式 close 与 phantom 兜底都会触发 cleanup, 由
 * [NativeCleanup.cleanupOnce] 保证只执行一次 native free。
 *
 * ctxPtr 全局查找: [findByCtxPtr] 供 JavaAdapter 回调时由 [JsFunctionHandle] 反向
 * 查找 ctxPtr 对应的 QuickJsContext (用于设置 ThreadLocal)。
 * 注册表持弱引用, 配合 GC 兜底路径 — 否则 ctxPtrRegistry 的强引用会阻止 ctx 被回收。
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

    /**
     * PhantomReference 兜底清理句柄。
     *
     * 持纯数值 (rtPtr/ctxPtr/scopeId), 不能持 [QuickJsContext] 实例引用 —
     * 否则形成强引用环, GC 永远不会回收本对象。显式 [close] 走同步路径,
     * GC 触发时走守护线程异步路径, [NativeCleanup.cleanupOnce] 保证幂等。
     */
    private val nativeCleanup: NativeCleanup

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
        // 清空身份去重映射, 让强引用尽早断开 (句柄本体由 releaseScope 统一释放)。
        // 即使 cleanupOnce 已被守护线程跑过, 重复 clear 也无副作用。
        identityHandles.clear()
        // 显式触发 native 释放; 已被 phantom 兜底执行过时是 no-op。
        nativeCleanup.cleanupOnce()
    }

    // ============ ctxPtr 全局注册表 (供 JavaAdapter 回调反向查找) ============

    /**
     * 把 (ctxPtr -> WeakRef<this>) 注册到全局表, 并注册 PhantomReference 兜底。
     * 在 [QuickJsEngine.getRuntimeScope] 创建 ctx 后调用。
     */
    init {
        registerCtxPtr()
        // 注: NativeCleanup 持 rtPtr/ctxPtr/scopeId 数值, 不持 this 引用,
        // 否则 PhantomReference 的 referent 路径上会有强引用环, 永不入队。
        nativeCleanup = NativeCleanup.register(this, rtPtr, ctxPtr, scopeId)
    }

    private fun registerCtxPtr() {
        ctxPtrRegistry[ctxPtr] = WeakReference(this)
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
         * ctxPtr -> WeakReference<QuickJsContext> 全局注册表。
         * 用于 JavaAdapter 回调时由 [JsFunctionHandle] 反向查找 ctxPtr 对应的 ctx,
         * 以便设置 [threadLocalContext] (供 binding handler 使用)。
         *
         * 弱引用: ctxPtrRegistry 不能阻止 ctx 被 GC, 否则 [NativeCleanup] 永不触发,
         * GC 兜底路径失效。回调期间 JS 必然栈上持强引用 ctx, weakRef.get() 一定可解。
         */
        private val ctxPtrRegistry =
            java.util.concurrent.ConcurrentHashMap<Long, WeakReference<QuickJsContext>>()

        /**
         * 按 ctxPtr 查找 QuickJsContext。
         * 用于 [JsFunctionHandle.invokeJsMethod] 设置 ThreadLocal。
         */
        @JvmStatic
        fun findByCtxPtr(ctxPtr: Long): QuickJsContext? {
            return ctxPtrRegistry[ctxPtr]?.get()
        }

        /**
         * 由 [NativeCleanup] 在守护线程或显式 close 时回调摘除 ctxPtr 条目。
         * 不接受 [QuickJsContext] 实例 — NativeCleanup 不能持有 ctx 引用。
         */
        internal fun unregisterCtxPtr(ctxPtr: Long) {
            ctxPtrRegistry.remove(ctxPtr)
        }
    }
}

/**
 * QuickJsContext 的 native 资源清理 token。
 *
 * 双触发路径:
 * 1. [QuickJsContext.close] 显式同步触发 (一次性 scope / 测试)。
 * 2. PhantomReference 入队 (GC 标记 ctx 不可达) → [Reaper] 守护线程异步触发
 *    (SharedJsScope 弱引用缓存路径)。
 *
 * 幂等: [cleanupOnce] 用 @Volatile + synchronized 双检, 任一路径先到都安全。
 * 字段只能持基本类型, 持有 ctx 引用会破坏 phantom 不可达检测。
 */
internal class NativeCleanup private constructor(
    referent: QuickJsContext,
    private val rtPtr: Long,
    private val ctxPtr: Long,
    private val scopeId: Long
) : PhantomReference<QuickJsContext>(referent, Reaper.queue) {

    @Volatile
    private var done: Boolean = false

    fun cleanupOnce() {
        if (done) return
        synchronized(this) {
            if (done) return
            done = true
        }
        // 从 registry 摘除, 让 PhantomRef 自身可被回收 (即便 GC 路径 happen 也已 no-op)
        Reaper.unregister(this)
        try {
            QuickJsContext.unregisterCtxPtr(ctxPtr)
            JavaObjectBridge.releaseScope(scopeId)
            JsFunctionHandle.releaseScope(scopeId)
            if (ctxPtr != 0L) {
                QuickJsNative.nativeFreeContext(ctxPtr)
            }
            if (rtPtr != 0L) {
                QuickJsNative.nativeFreeRuntime(rtPtr)
            }
        } catch (t: Throwable) {
            android.util.Log.e("QuickJsCleaner", "cleanupOnce failed", t)
        } finally {
            // 清除 referent 链, 帮助 PhantomRef 尽早回收
            clear()
        }
    }

    companion object {
        fun register(
            ctx: QuickJsContext,
            rtPtr: Long,
            ctxPtr: Long,
            scopeId: Long
        ): NativeCleanup {
            val ref = NativeCleanup(ctx, rtPtr, ctxPtr, scopeId)
            // 必须持强引用, 否则 PhantomRef 自身被 GC 后无法入队
            Reaper.register(ref)
            return ref
        }
    }

    /**
     * 守护线程 + ReferenceQueue 单例, 拉起一次, 进程内长驻。
     */
    private object Reaper {
        val queue = ReferenceQueue<QuickJsContext>()

        private val pending =
            java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<NativeCleanup, Boolean>()
            )

        init {
            Thread({
                while (true) {
                    try {
                        val ref = queue.remove()
                        (ref as? NativeCleanup)?.cleanupOnce()
                    } catch (_: InterruptedException) {
                        // 守护线程持续运行, 不响应中断
                    } catch (t: Throwable) {
                        android.util.Log.e("QuickJsCleaner", "reaper loop error", t)
                    }
                }
            }, "QuickJsScopeCleaner").apply {
                isDaemon = true
                start()
            }
        }

        fun register(ref: NativeCleanup) {
            pending.add(ref)
        }

        fun unregister(ref: NativeCleanup) {
            pending.remove(ref)
        }
    }
}
