package io.legado.app.help.media

import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 朗读 / 音频播放共用的关闭定时器。
 *
 * 行为约定:
 * - minutes == 0 表示未启用,不启动计时协程
 * - addTimer 在 [10,180] 之间循环,达到 180 后下一次按下回到 0(取消定时)
 * - 每分钟在 [isPaused] 为 false 时递减 1,到 0 时回调 [onTimeout]
 * - 任何状态变更都会通过 EventBus [eventKey] 广播当前剩余分钟,并触发 [onTick] 让宿主刷新通知
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val eventKey: String,
    private val isPaused: () -> Boolean,
    private val onTimeout: () -> Unit,
    private val onTick: () -> Unit = {}
) {

    @Volatile
    var minutes: Int = 0
        private set

    private var job: Job? = null

    fun set(min: Int) {
        minutes = min
        restart()
    }

    fun add() {
        minutes = if (minutes == MAX_MIN) 0 else (minutes + STEP_MIN).coerceAtMost(MAX_MIN)
        restart()
    }

    @Synchronized
    private fun restart() {
        postEvent(eventKey, minutes)
        onTick()
        job?.cancel()
        if (minutes <= 0) return
        job = scope.launch {
            while (isActive) {
                delay(60_000)
                if (!isPaused()) {
                    minutes--
                    if (minutes <= 0) {
                        minutes = 0
                        onTimeout()
                        postEvent(eventKey, 0)
                        onTick()
                        break
                    }
                }
                postEvent(eventKey, minutes)
                onTick()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    companion object {
        private const val MAX_MIN = 180
        private const val STEP_MIN = 10
    }
}
