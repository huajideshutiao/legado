package io.legado.app.help.coroutine

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 单协程槽: 世代号 + 当前协程整份 CAS 发布。
 *
 * 消费方是同一份监听/计时任务被多个线程反复重建的场景 (UI 线程与后台协程各自执行
 * "取消旧的 + 启动新的"): 裸 [Job] 字段的这两步是两次独立写, 后到的旧请求会在新请求之后
 * 覆盖协程槽, 让已被取消的协程复活且无人再取消它; @Volatile 只解决可见性, 解决不了复合写。
 *
 * 保证 (无锁, 抢占与发布都是 [atomic] 的 CAS):
 * - [restart] 一定取消调用时刻最新的协程; 被它取代的旧请求即使随后才启动, 也不会留下第二个
 *   活协程: 协程先入槽后启动, 启动前就被抢占的一代整份作废, block 一行都不执行
 * - [cancel] 一定落到调用时刻最新的协程上, 且此后旧请求的 [restart] 无法让协程绕过它复活
 *
 * 世代号只在本类内部用 (标识当前已发布的快照), 不交给协程体: 被取代的一代要么从未启动过,
 * 要么已被 [Job.cancel], 调用点无从也无需自检 —— 判断自己是否还该继续用 [isActive]
 * 或挂起点上的 `ensureActive()`。
 */
class SingleJobSlot(private val scope: CoroutineScope) {

    /** 世代号 + 该世代的协程 (未启动完成时为 null), 整份发布。 */
    private class Slot(val generation: Long, val job: Job?)

    private val slot = atomic(Slot(0L, null))

    /** 当前协程是否仍在运行。 */
    val isActive: Boolean get() = slot.value.job?.isActive == true

    /** 取消当前协程并启动 [block]。 */
    fun restart(block: suspend () -> Unit) {
        start(seize(), block)
    }

    /**
     * 当前协程仍在运行时原样保留, 否则同 [restart]。
     *
     * 用于长驻监听的补建: 重建会丢掉订阅切换间隙里的事件, 让等待中的状态再也等不到更新。
     */
    fun restartIfInactive(block: suspend () -> Unit) {
        if (isActive) return
        start(seize(), block)
    }

    /** 取消当前协程并推进世代: 抢在取消之前入槽的那一代会据此作废。 */
    fun cancel() {
        seize()
    }

    /** 抢占: 推进世代并取走当前协程, 返回新世代号。并发下只有一个调用者拿到旧协程。 */
    private fun seize(): Long {
        while (true) {
            val cur = slot.value
            val next = Slot(cur.generation + 1, null)
            if (slot.compareAndSet(cur, next)) {
                cur.job?.cancel()
                return next.generation
            }
        }
    }

    /**
     * 先建未启动的协程、入槽、再启动; 入槽与启动之间被更晚的请求抢占 (世代已变) 时
     * 整份作废 —— 同一个槽只留最新一代, 且被取代的一代不会执行 body。
     *
     * 顺序反过来 (先启动后入槽) 时, 被取代的一代在 [Job.cancel] 之前已经跑起来了。
     */
    private fun start(generation: Long, block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        while (true) {
            val cur = slot.value
            if (cur.generation != generation) {
                // 未启动的 LAZY 协程 cancel 即完成, block 不会执行
                job.cancel()
                return
            }
            if (slot.compareAndSet(cur, Slot(generation, job))) {
                job.start()
                return
            }
        }
    }
}
