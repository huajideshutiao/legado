package io.legado.app.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * [platformSleep] 的 iOS/鸿蒙 actual: 用 [runBlocking] + [delay] 实现阻塞语义。
 *
 * 详见 commonMain/utils/PlatformSleep.kt expect 注释。
 * 行为与 jvmAndAndroidMain 的 Thread.sleep(millis) 等价 (阻塞当前线程)。
 *
 * 注: Kotlin/Native 中 Thread.sleep 不是稳定 API, 改用 runBlocking { delay(millis) };
 * 在主线程调用可能造成 deadlock (与 JVM Thread.sleep 行为不同), 但调用方均为后台阻塞路径
 * (如 ConcurrentRateLimiter.getConcurrentRecordBlocking), 与 JVM 行为对齐。
 */
internal actual fun platformSleep(millis: Long) {
    runBlocking { delay(millis) }
}
