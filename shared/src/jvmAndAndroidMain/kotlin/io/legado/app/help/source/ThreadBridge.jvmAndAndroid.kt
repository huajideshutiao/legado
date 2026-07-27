package io.legado.app.help.source

import java.util.concurrent.locks.LockSupport

/**
 * [currentThreadMarker] / [parkThread] / [unparkThread] 的 JVM/Android actual。
 *
 * 委托 [LockSupport] 保持原 app 端 `SourceVerificationHelp` 的 parkNanos/unpark 行为
 * (可被 unpark 提前唤醒, 避免固定等待整轮 waitTime, 与下沉前完全一致)。
 *
 * 注: 原代码 `LockSupport.parkNanos(this, waitTime)` 带 blocker 参数仅用于诊断工具
 * (如 jstack), 不影响阻塞行为; 此处简化为无 blocker 版本 `parkNanos(nanos)`,
 * 阻塞/唤醒语义完全等价。
 */
internal actual fun currentThreadMarker(): Any = Thread.currentThread()

internal actual fun parkThread(nanos: Long) {
    LockSupport.parkNanos(nanos)
}

internal actual fun unparkThread(marker: Any?) {
    (marker as? Thread)?.let { LockSupport.unpark(it) }
}
