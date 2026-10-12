package io.legado.app.help.media

import io.legado.app.help.coroutine.SingleJobSlot
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * 朗读 / 音频播放共用的关闭定时器。
 *
 * 行为约定:
 * - minutes == 0 表示未启用,不启动计时协程
 * - addTimer 在 [10,180] 之间循环,达到 180 后下一次按下回到 0(取消定时)
 * - 每分钟在 [isPaused] 为 false 时递减 1,到 0 时回调 [onTimeout]
 * - 任何状态变更都会通过 [postMinute] 广播当前剩余分钟,并触发 [onTick] 让宿主刷新通知
 *
 * 线程: [set] / [add] / [cancel] 由命令线程调用 (UI / 通知 / 播控), 递减与广播在计时协程执行。
 * 剩余分钟与计时世代存在同一份不可变快照里 ([atomic] 发布), 递减的 CAS 键含世代号 —— 世代已变
 * 时那一减不生效, 用户刚写入的分钟数不会被覆盖, 因此不会比设定早停; 被取代的协程立即退出,
 * 不再递减也不再广播。到点回调与用户重设定时同时发生时, 回调无法与发布同一步原子完成, 仍按
 * 到点处理 (回调前已再校验一次世代, 窗口限于校验到回调之间)。
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val postMinute: (Int) -> Unit,
    private val isPaused: () -> Boolean,
    private val onTimeout: () -> Unit,
    private val onTick: () -> Unit = {}
) {

    /**
     * 剩余分钟 + 计时世代 (同一份不可变快照, 原子发布)。
     *
     * 世代号每次 [set] / [add] / [cancel] 递增, 递减只在世代未变时才能成功 —— 读改写不再分两步
     * 落在两个字段上, 也就没有"递减已写入、世代才发现已变"的半个操作。
     */
    private class Countdown(val generation: Long, val minutes: Int)

    private val countdown = atomic(Countdown(0L, 0))

    val minutes: Int get() = countdown.value.minutes

    /** 计时协程槽: set/add/cancel 都经 [SingleJobSlot] 抢占世代。 */
    private val timer = SingleJobSlot(scope)

    fun set(min: Int) {
        publish { Countdown(it.generation + 1, min) }
        restart()
    }

    fun add() {
        publish { cur ->
            val next = if (cur.minutes == MAX_MIN) {
                0
            } else {
                (cur.minutes + STEP_MIN).coerceAtMost(MAX_MIN)
            }
            Countdown(cur.generation + 1, next)
        }
        restart()
    }

    fun cancel() {
        // 世代自增: 已在跑的递减立即作废, 取消后不会再有人改动读数
        publish { Countdown(it.generation + 1, it.minutes) }
        timer.cancel()
    }

    /** 整份发布新读数 (世代自增): 递减只有在世代未变时才能覆盖它。 */
    private fun publish(transform: (Countdown) -> Countdown) {
        while (true) {
            val cur = countdown.value
            val next = transform(cur)
            if (countdown.compareAndSet(cur, next)) return
        }
    }

    private fun restart() {
        // 一次取值: 广播的分钟数与本次是否启动计时同源
        val current = countdown.value
        postMinute(current.minutes)
        onTick()
        if (current.minutes <= 0) {
            timer.cancel()
            return
        }
        timer.restart { countDown() }
    }

    /**
     * 每分钟递减一次; 世代已变 ([set] / [add] / [cancel] 取代了本次计时) 时立即退出,
     * 不再对新的一代递减, 也不广播自己的旧读数。
     */
    private suspend fun countDown() {
        // 世代取自启动时刻: 之后 set/add 发布的新世代由它自己的协程负责
        val generation = countdown.value.generation
        while (true) {
            delay(60_000)
            if (countdown.value.generation != generation) return
            if (!isPaused()) {
                var remaining: Int
                while (true) {
                    val cur = countdown.value
                    if (cur.generation != generation) return
                    remaining = if (cur.minutes <= 1) 0 else cur.minutes - 1
                    if (countdown.compareAndSet(cur, Countdown(generation, remaining))) break
                }
                if (remaining <= 0) {
                    // 世代已变说明用户刚重设定时: 本次到点作废, 不能停掉新一轮
                    if (countdown.value.generation != generation) return
                    onTimeout()
                    postMinute(0)
                    onTick()
                    return
                }
            }
            postMinute(countdown.value.minutes)
            onTick()
        }
    }

    companion object {
        private const val MAX_MIN = 180
        private const val STEP_MIN = 10
    }
}
