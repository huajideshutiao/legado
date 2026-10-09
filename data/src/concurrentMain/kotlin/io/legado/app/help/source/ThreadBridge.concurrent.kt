@file:OptIn(ExperimentalThreadBlockingApi::class)

package io.legado.app.help.source

import kotlinx.atomicfu.locks.ExperimentalThreadBlockingApi
import kotlinx.atomicfu.locks.ParkingHandle
import kotlinx.atomicfu.locks.ParkingSupport
import kotlin.time.Duration.Companion.nanoseconds

/**
 * [currentThreadMarker] / [parkThread] / [unparkThread] 的 actual (jvm + android + ios + 鸿蒙 共用一份)。
 *
 * # 为什么是 concurrentMain 而不是 commonMain
 * [ParkingSupport] / [ParkingHandle] / [ExperimentalThreadBlockingApi] 声明在 kotlinx-atomicfu 的
 * `concurrentMain` 源集 (`atomicfu-metadata` 里对应 `concurrentMain/default/linkdata/...` 片段),
 * 其实现按目标分岔: jvm 侧 actual 就是 `LockSupport` (ParkingHandle = Thread typealias,
 * park→LockSupport.park/parkNanos, unpark→LockSupport.unpark), native 侧 actual 是
 * `pthread_cond_timedwait` / `pthread_cond_signal`。该片段对 commonMain 不可见
 * (`kotlin-project-structure-metadata.json` 里 `jvmApiElements` 的 sourceSet 只列到
 * `concurrentMain`/`commonMain`), 故把 actual 放在与 atomicfu concurrent 同级的源集
 * `data/src/concurrentMain/kotlin` (声明见 data/build.gradle.kts 的 `concurrentMain`)。
 *
 * # 运行时行为逐字不变
 * - jvm / android: [ParkingSupport] 的 jvm actual 直接转发 `java.util.concurrent.locks.LockSupport`
 *   (`ParkingSupport.currentThreadHandle()` ≡ `Thread.currentThread()`,
 *   `park(nanos.nanoseconds)` ≡ `LockSupport.parkNanos(nanos)`,
 *   `unpark(handle)` ≡ `LockSupport.unpark(thread)`) —— 与下沉前的
 *   `ThreadBridge.jvmAndAndroid.kt` (直接调 LockSupport) 是同一批 JDK 调用;
 * - native (iOS / 鸿蒙): pthread cond 实现, 与下沉前的 `ThreadBridge.native.kt` 逐字相同。
 *
 * # 语义如实记录
 * - [ParkingSupport.park] 允许伪唤醒 (spurious wakeup); 调用方 [SourceVerificationHelpShared]
 *   的轮询循环本就以"醒来后重新检查结果"为契约 (与 jvm 端 `LockSupport` 同款容忍), 不受影响;
 * - 传 0/负纳秒不等待 (Duration 为负时 park 立即返回), 与 `LockSupport.parkNanos(0)` 一致;
 * - 提前发出的 unpark 会被下一次 park 立即消费 (ParkingSupport 自带许可语义, 与 LockSupport 一致)。
 *
 * 合并说明: 原先 jvmAndAndroidMain / nativeMain 各一份 actual 文件, 现只剩本文件
 * (源集声明见 data/build.gradle.kts 的 `concurrentMain`)。
 */
internal actual fun currentThreadMarker(): Any = ParkingSupport.currentThreadHandle()

internal actual fun parkThread(nanos: Long) {
    ParkingSupport.park(nanos.nanoseconds)
}

internal actual fun unparkThread(marker: Any?) {
    (marker as? ParkingHandle)?.let { ParkingSupport.unpark(it) }
}
