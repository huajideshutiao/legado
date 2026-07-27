package io.legado.app.utils

/**
 * 阻塞当前线程指定毫秒数的平台抽象。
 *
 * commonMain 无 `Thread.sleep` 直接等价物, 抽出为 expect/actual 供需要"阻塞语义"
 * 的代码 (如 [io.legado.app.help.ConcurrentRateLimiter.getConcurrentRecordBlocking])
 * 下沉 commonMain。
 *
 * - JVM/Android actual: `Thread.sleep(millis)` (行为不变)
 *
 * 调用方应仅在无 suspend 语境的阻塞路径使用; suspend 路径请用 `kotlinx.coroutines.delay`。
 */
internal expect fun platformSleep(millis: Long)
