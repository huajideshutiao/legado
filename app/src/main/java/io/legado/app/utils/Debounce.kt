package io.legado.app.utils

import android.os.SystemClock
import kotlin.math.max

// 未下沉至 shared/jvmAndAndroidMain 原因:
// 本类通过 buildMainHandler() 获取 Android 主线程 Handler, trailing/maxWait 回调
// 在主线程执行。调研调用方:
//   1. ReadBookActivity.upSeekBarThrottle: 回调内已 runOnUiThread { ... }, 不依赖.
//   2. ReadMangaActivity.nextPageThrottle/prevPageThrottle: trailing=false, 仅 leading
//      同步执行, 不依赖.
//   3. ReadView.upProgressThrottle: 回调内已 post { ... }, 不依赖.
//   4. ReadBookActivity.keyPageDebounce 的 nextPageDebounce/prevPageDebounce:
//      鼠标滚轮场景 leading=false/trailing=true, trailing 回调直接执行 keyPage() ->
//      keyTurnPage() -> nextPageByAnim() -> abortAnim()/readView.setStartPoint()/
//      onAnimStart() 等 View 动画操作, 严格依赖主线程.
// 方案 A (ScheduledExecutorService + Dispatchers.Main 切回) 不可行: jvmAndAndroidMain
// 无 Dispatchers.Main actual (kotlinx-coroutines-android 仅在 androidMain, 见 shared
// build.gradle).
// 方案 B (纯 ScheduledExecutorService) 会破坏 case 4: trailing 回调跑到工作线程引发
// UI 线程违例.
// 故保留 app 端实现, 维持 buildMainHandler() 主线程 Handler 语义.
@Suppress("MemberVisibilityCanBePrivate")
open class Debounce<T>(
    var wait: Long = 0L,
    var maxWait: Long = -1L,
    var leading: Boolean = false,
    var trailing: Boolean = true,
    private val func: () -> T
) {
    companion object {
        private val handler by lazy { buildMainHandler() }
    }

    private var lastCallTime = -1L
    private var lastInvokeTime = 0L
    private val maxing get() = maxWait != -1L
    private var result: T? = null
    private var hasTimer = false
    private val timerExpiredRunnable = Runnable {
        timerExpired()
    }

    init {
        maxWait = if (maxing) max(maxWait, wait) else maxWait
    }

    private fun invokeFunc(time: Long): T {
        lastInvokeTime = time
        return func.invoke().also { result = it }
    }

    private fun startTimer(wait: Long) {
        hasTimer = true
        handler.postDelayed(timerExpiredRunnable, wait)
    }

    private fun cancelTimer() {
        handler.removeCallbacks(timerExpiredRunnable)
    }

    private fun leadingEdge(time: Long): T? {
        lastInvokeTime = time
        startTimer(wait)
        return if (leading) invokeFunc(time) else result
    }

    private fun trailingEdge(time: Long): T? {
        hasTimer = false
        return if (trailing) invokeFunc(time) else result
    }

    private fun remainingWait(time: Long): Long {
        val timeSinceLastCall = time - lastCallTime
        val timeSinceLastInvoke = time - lastInvokeTime
        val timeWaiting = wait - timeSinceLastCall

        return if (maxing) timeWaiting.coerceAtMost(maxWait - timeSinceLastInvoke) else timeWaiting
    }

    private fun shouldInvoke(time: Long): Boolean {
        val timeSinceLastCall = time - lastCallTime
        val timeSinceLastInvoke = time - lastInvokeTime

        return lastCallTime == -1L
                || timeSinceLastCall >= wait
                || timeSinceLastCall < 0
                || maxing && timeSinceLastInvoke >= maxWait
    }

    private fun timerExpired() {
        val time = SystemClock.uptimeMillis()
        if (shouldInvoke(time)) {
            trailingEdge(time)
        } else {
            startTimer(remainingWait(time))
        }
    }

    fun cancel() {
        if (hasTimer) {
            cancelTimer()
        }
        lastInvokeTime = 0
        lastCallTime = -1L
        hasTimer = false
    }

    fun flush(): T? {
        return if (hasTimer) trailingEdge(SystemClock.uptimeMillis()) else result
    }

    operator fun invoke(): T? {
        val time = SystemClock.uptimeMillis()
        val isInvoking = shouldInvoke(time)

        lastCallTime = time

        if (isInvoking) {
            if (!hasTimer) {
                return leadingEdge(lastCallTime)
            }
            if (maxing) {
                startTimer(wait)
                return invokeFunc(lastCallTime)
            }
        }

        if (!hasTimer) {
            startTimer(wait)
        }

        return result
    }

}

