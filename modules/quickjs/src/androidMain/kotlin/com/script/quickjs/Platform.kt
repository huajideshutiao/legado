package com.script.quickjs

import android.util.Log

/**
 * Android 平台 actual 实现: 复用 android.util.Log + System.loadLibrary。
 *
 * 不改变 Android 端现有行为, 与改造前完全等价。
 */
actual fun logQuickJsError(tag: String, msg: String, e: Throwable?) {
    Log.e(tag, msg, e)
}

actual fun logQuickJsWarn(tag: String, msg: String, e: Throwable?) {
    Log.w(tag, msg, e)
}

actual fun loadLegadoQuickJsNative() {
    System.loadLibrary("legado_quickjs")
}

// ============ 计时器平台唤醒 (Android) ============

// 协约: 计时器回调只在 JS 线程由 QuickJsAsync.settle 触发 (quickjs ctx 单线程),
// 主线程 Handler 只负责到点唤醒等待中的 JS 线程 (signalWake); JS 线程不在等待时
// signalWake 是 no-op, 计时器会在下次 settle 按 deadline 触发。
// 唤醒 Runnable 只持 JsTimerManager 弱引用: ctx 被 GC 后 pending callback 不阻接 GC。
actual fun platformScheduleTimerWake(tm: JsTimerManager, deadlineMs: Long) {
    val handler = mainHandlerOrNull() ?: return
    val delay = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0)
    val weak = java.lang.ref.WeakReference(tm)
    val runnable = Runnable { weak.get()?.signalWake() }
    handler.postDelayed(runnable, delay)
    tm.addWakeToken(runnable)
}

actual fun platformCancelAllTimerWakes(tm: JsTimerManager) {
    val handler = mainHandlerOrNull() ?: return
    for (token in tm.drainWakeTokens()) {
        handler.removeCallbacks(token as Runnable)
    }
}

/** 主线程 Looper 不可用 (headless/测试进程) 时返回 null, 降级为 awaitNanos 自醒。 */
private fun mainHandlerOrNull(): android.os.Handler? {
    return try {
        val looper = android.os.Looper.getMainLooper()
        if (looper == null) null else android.os.Handler(looper)
    } catch (_: Throwable) {
        null
    }
}
