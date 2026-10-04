package com.script.quickjs

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Promise/计时器宿主泵送层。
 *
 * 外部约束: quickjs-ng 引擎自身不泵送 pending job (JS_EnqueueJob / JS_ExecutePendingJob
 * 只是原语), 泵送是宿主职责。本文件在每次 JS 求值边界后:
 * 1. 循环 [QuickJsNative.nativePumpJobs] 把 async/await 的微任务链同步 settle;
 * 2. 有 Promise 结果时先泵送再读取 resolved 值 (拒绝路径抛错, 可观测);
 * 3. 支持 setTimeout/clearTimeout/setInterval/clearInterval 计时器: 回调只在 JS 线程
 *    由 settle 循环触发 (JVM 端 awaitNanos 自醒, Android 端经主线程 Handler 唤醒 +
 *    JS 线程泵送协约, 见 [platformScheduleTimerWake])。
 *
 * 线程模型: settle 必须在持有 ctx 的 JS 执行线程调用 (eval/nativeCall 返回后)。
 */
object QuickJsAsync {

    /** 单次 settle 允许的最大计时器触发次数 (防 setInterval 死循环把 eval 挂死)。 */
    private const val MAX_TIMER_FIRES = 10_000

    /** 单次 settle 允许的最大总等待毫秒 (等待 promise 依赖的计时器时)。 */
    private const val MAX_TOTAL_WAIT_MS = 30_000L

    /**
     * 泵送微任务 + 触发到期计时器, 直到无更多可执行工作。
     *
     * @param awaitResult 为 true 时, 若无微任务且结果仍未 settle, 阻塞等待最近计时器
     *    deadline (预算受 [MAX_TOTAL_WAIT_MS] / [MAX_TIMER_FIRES] 限制)。仅在求值结果是
     *    Promise (JS 显式等待异步完成) 时传 true; 回调边界传 false 避免阻塞宿主线程。
     *
     * job / 计时器回调抛错会以 [JsNativeException] 上抛 (可观测, 不静默)。
     */
    fun settle(ctx: QuickJsContext, awaitResult: Boolean) {
        val tm = ctx.timerManager
        var fires = 0
        var waitedTotal = 0L
        val prev = QuickJsContext.threadLocalContext.get()
        if (prev !== ctx) QuickJsContext.threadLocalContext.set(ctx)
        try {
            while (true) {
                // 1) 微任务泵送: job 抛错 -> JsNativeException 上抛
                QuickJsNative.nativePumpJobs(ctx.ctxPtr)
                // 2) 触发已到期计时器 (JS 线程执行回调, 可能产生新微任务 -> 回到 1)
                if (tm.fireDueTimers()) {
                    if (++fires >= MAX_TIMER_FIRES) break
                    continue
                }
                // 3) 无微任务且无到期计时器: 等待结果时阻塞到最近 deadline (预算内)
                if (awaitResult && tm.hasPendingTimers() && waitedTotal < MAX_TOTAL_WAIT_MS) {
                    val deadline = tm.nextDeadlineMs()
                    val now = System.currentTimeMillis()
                    if (deadline > now) {
                        val budget = MAX_TOTAL_WAIT_MS - waitedTotal
                        val waitMs = minOf(deadline - now, budget)
                        if (waitMs > 0) {
                            tm.awaitUntilDeadline(waitMs)
                            waitedTotal += waitMs
                        }
                        continue
                    }
                }
                break
            }
        } finally {
            if (prev !== ctx) QuickJsContext.threadLocalContext.set(prev)
        }
    }

    /**
     * 求值/调用返回值的统一后处理: 若结果是 Promise, 先泵送再读 resolved 值。
     *
     * - fulfilled -> resolved 值 (Promise 句柄随之释放)
     * - rejected  -> 抛 [JsNativeException] (拒绝原因, 可观测)
     * - 超预算仍未 settle -> 原样返回 Promise 句柄 (保留后续 settle 的机会)
     */
    fun settleAndUnwrap(ctx: QuickJsContext, result: Any?): Any? {
        val handle = (result as? Number)?.toLong()
        if (handle == null || handle == 0L ||
            !QuickJsNative.nativeIsPromise(ctx.ctxPtr, handle)
        ) {
            settle(ctx, awaitResult = false)
            return result
        }
        settle(ctx, awaitResult = true)
        val state = QuickJsNative.nativePromiseState(ctx.ctxPtr, handle)
        return try {
            when (state) {
                1 -> QuickJsNative.nativePromiseResult(ctx.ctxPtr, handle)
                2 -> QuickJsNative.nativePromiseResult(ctx.ctxPtr, handle)
                else -> result
            }
        } finally {
            // Promise 句柄已消费 (resolved 值已转换), 释放; pending 情况原样交给调用方
            if (state == 1 || state == 2) {
                QuickJsNative.nativeFreeHandle(ctx.ctxPtr, handle)
            }
        }
    }
}

/**
 * 每 ctx 的计时器注册表。回调只在 JS 线程由 [QuickJsAsync.settle] 触发, 线程模型与 ctx 一致。
 *
 * 设计取舍 (对齐 quickjs-libc std timer 语义的子集):
 * - setTimeout/clearTimeout/setInterval/clearInterval, 不支持 unref/ref 语义
 * - 回调延迟毫秒级截断, 最小 0 (下一个 settle 循环立即触发)
 * - 计时器回调参数仅保证基本类型; JS 对象/函数参数经 binding 往返会退化为句柄数字
 *   (nativeCallJsHandle 的 fromJavaObject 把 Long 当数字, 已知限制)
 * - 计时器只在宿主泵送 (settle) 时触发: 无 Promise 等待的 eval 后, 已注册但未到期的
 *   计时器保留到下一次 settle (JS 线程), 不跨线程执行 JS
 */
class JsTimerManager internal constructor(private val ctx: QuickJsContext) {

    private class Timer(
        val fnHandle: Long,
        val args: Array<Any?>,
        var deadlineMs: Long,
        val intervalMs: Long,
        var active: Boolean = true
    )

    private val lock = ReentrantLock()
    private val waitCond = lock.newCondition()
    private val timers = LinkedHashMap<Long, Timer>()
    private var nextId = 1L

    /** 平台唤醒回调 token (Android: Handler Runnable; JVM: 无), close 时统一取消。 */
    private val wakeTokens = java.util.Collections.synchronizedList(mutableListOf<Any>())

    /**
     * 注册计时器, 返回 id。
     *
     * @param fnHandle JS 回调函数句柄 (binding 参数转换所得, 本表持有引用)
     * @param args 回调参数 (基本类型)
     * @param repeat true = setInterval (触发后按间隔重新排期), false = setTimeout
     */
    fun schedule(fnHandle: Long, args: Array<Any?>, delayMs: Long, repeat: Boolean): Long {
        val delay = delayMs.coerceAtLeast(0)
        val deadline = System.currentTimeMillis() + delay
        val id: Long
        lock.lock()
        try {
            id = nextId++
            timers[id] = Timer(
                fnHandle,
                args,
                deadline,
                if (repeat) delay.coerceAtLeast(1) else 0
            )
            waitCond.signalAll()   // 新计时器可能早于当前等待的 deadline
        } finally {
            lock.unlock()
        }
        // Android 端经主线程 Handler 调度唤醒; JVM 端 no-op (awaitNanos 自醒)
        platformScheduleTimerWake(this, deadline)
        return id
    }

    /** 取消计时器, 释放其回调函数句柄。 */
    fun clear(id: Long) {
        var fnHandle = 0L
        lock.lock()
        try {
            val t = timers.remove(id)
            if (t != null) {
                t.active = false
                fnHandle = t.fnHandle
            }
        } finally {
            lock.unlock()
        }
        if (fnHandle != 0L) QuickJsNative.nativeFreeHandle(ctx.ctxPtr, fnHandle)
    }

    /** 是否仍有未取消计时器。 */
    fun hasPendingTimers(): Boolean {
        lock.lock()
        try {
            return timers.isNotEmpty()
        } finally {
            lock.unlock()
        }
    }

    /** 最近 deadline (epoch ms); 无计时器返回 [Long.MAX_VALUE]。 */
    fun nextDeadlineMs(): Long {
        lock.lock()
        try {
            var min = Long.MAX_VALUE
            for (t in timers.values) {
                if (t.active && t.deadlineMs < min) min = t.deadlineMs
            }
            return min
        } finally {
            lock.unlock()
        }
    }

    /**
     * 阻塞至最近计时器 deadline 或收到唤醒信号 (平台无关: Condition.awaitNanos)。
     * 新计时器注册 / 取消都会 signalAll 提前唤醒, 重新评估。
     */
    fun awaitUntilDeadline(maxWaitMs: Long) {
        lock.lock()
        try {
            while (true) {
                val dl = nextDeadlineMs()
                if (dl == Long.MAX_VALUE) return
                val now = System.currentTimeMillis()
                if (now >= dl) return
                val waitMs = minOf(dl - now, maxWaitMs)
                if (waitMs <= 0) return
                waitCond.awaitNanos(TimeUnit.MILLISECONDS.toNanos(waitMs))
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * 触发所有已到期计时器 (必须 JS 线程调用, 回调内可调用 Java binding)。
     * @return 是否触发过至少一个回调
     */
    fun fireDueTimers(): Boolean {
        val now = System.currentTimeMillis()
        lock.lock()
        val due = ArrayList<Pair<Long, Timer>>()
        try {
            for ((id, t) in timers) {
                if (t.active && t.deadlineMs <= now) due.add(id to t)
            }
        } finally {
            lock.unlock()
        }
        if (due.isEmpty()) return false
        var fired = false
        val prev = QuickJsContext.threadLocalContext.get()
        QuickJsContext.threadLocalContext.set(ctx)
        try {
            for ((id, t) in due) {
                // 触发前复查未取消 (防御: 快照与触发之间理论上无 JS 执行, 但保持与 clear 一致)
                lock.lock()
                val alive = try { timers[id] === t && t.active } finally { lock.unlock() }
                if (!alive) continue
                if (t.intervalMs > 0) {
                    // setInterval: 触发前重新排期 (相对当前时间, 防回调耗时堆积)
                    lock.lock()
                    try {
                        t.deadlineMs = System.currentTimeMillis() + t.intervalMs
                    } finally {
                        lock.unlock()
                    }
                }
                // 回调执行期间函数句柄必须存活: 先 dup 一份再调用, 否则回调内
                // clearTimeout/clearInterval 自己的 id 时 clear() 会释放该函数对象
                // (表内唯一引用) 造成 use-after-free (堆腐败, 下次测试才崩溃)。
                val callHandle = QuickJsNative.nativeDupHandle(ctx.ctxPtr, t.fnHandle)
                if (callHandle == 0L) continue   // 已被并发释放 (理论不可达)
                try {
                    // 回调抛错向上传播 (可观测); 一次性计时器在 finally 中移除, interval 已重新排期
                    QuickJsNative.nativeCallJsHandle(callHandle, t.args)
                    fired = true
                } finally {
                    QuickJsNative.nativeFreeHandle(ctx.ctxPtr, callHandle)
                    if (t.intervalMs <= 0) {
                        // 一次性计时器: 触发后移除并释放回调函数句柄
                        lock.lock()
                        try {
                            if (timers[id] === t) {
                                timers.remove(id)
                                t.active = false
                            }
                        } finally {
                            lock.unlock()
                        }
                        QuickJsNative.nativeFreeHandle(ctx.ctxPtr, t.fnHandle)
                    }
                }
            }
        } finally {
            QuickJsContext.threadLocalContext.set(prev)
        }
        return fired
    }

    /** 取消全部计时器 (ctx close 时调用), 释放回调句柄并取消平台唤醒回调。 */
    fun cancelAll() {
        lock.lock()
        val fns = ArrayList<Long>()
        try {
            for ((_, t) in timers) {
                t.active = false
                fns.add(t.fnHandle)
            }
            timers.clear()
            waitCond.signalAll()
        } finally {
            lock.unlock()
        }
        for (f in fns) QuickJsNative.nativeFreeHandle(ctx.ctxPtr, f)
        platformCancelAllTimerWakes(this)
    }

    /** 平台层追加唤醒 token (schedule 时注册, close 时取消)。 */
    fun addWakeToken(token: Any) {
        wakeTokens.add(token)
    }

    /** 取走并清空全部唤醒 token (平台层 close 时取消回调)。 */
    fun drainWakeTokens(): List<Any> {
        synchronized(wakeTokens) {
            if (wakeTokens.isEmpty()) return emptyList()
            val out = ArrayList<Any>(wakeTokens)
            wakeTokens.clear()
            return out
        }
    }

    /** 唤醒等待中的 JS 线程 (Android Handler 回调或新计时器注册时)。 */
    fun signalWake() {
        lock.lock()
        try {
            waitCond.signalAll()
        } finally {
            lock.unlock()
        }
    }
}
