package io.legado.app.help.source

/**
 * 线程挂起/唤醒跨平台桥接 (共用层, commonMain 唯一一份声明)。
 *
 * [SourceVerificationHelpShared] 的"验证结果 / 登录界面关闭"等待语义是
 * "挂起当前 JS 线程, 结果到达时由 UI 线程唤醒", 平台原语不同, 故这里只保留
 * expect 声明 + 契约, actual 只需一份 (原先 jvmAndAndroidMain / nativeMain 各一份,
 * 已合并):
 *
 * - `data/src/concurrentMain/.../ThreadBridge.concurrent.kt` (jvm + android + ios + 鸿蒙 共用):
 *   kotlinx-atomicfu `ParkingSupport` (`currentThreadHandle` / `park(Duration)` / `unpark`),
 *   其 jvm actual 就是 `LockSupport` (`ParkingHandle` = `Thread` typealias,
 *   park→`LockSupport.park/parkNanos`, unpark→`LockSupport.unpark`), native actual 用
 *   pthread cond 实现 —— 与两端原先各自的实现逐字等价
 *
 * 为何不直接放 commonMain: `ParkingSupport` 声明在 atomicfu 的 concurrent 级片段里
 * (jvm/native 都有 actual, js/wasm 没有), 只有 concurrent 级源集才解析得到
 * (源集声明见 data/build.gradle.kts 的 `concurrentMain`)。
 *
 * # 两端共同契约 (actual 必须满足, 否则 [SourceVerificationHelpShared] 的轮询循环会被拖慢)
 * - `park` 可被 `unpark` 提前唤醒: 提前发出的 `unpark` 会被下一次 `park` 立即消费, 不再等满整轮时长;
 * - `park` 允许伪唤醒 —— 调用方的循环本就以"醒来后重新检查结果"为契约;
 * - 传 0 / 负纳秒视为不等待 (JVM `LockSupport.parkNanos(0)` 行为)。
 */

/**
 * 当前线程的跨平台标记 (JVM 为 `Thread`, Native 为 `ParkingHandle`)。
 * 用于 [SourceVerificationHelpShared.registerWaitingThread] /
 * [SourceVerificationHelpShared.notifyResultArrived]。
 *
 * 标记只被原样传递与交回, 调用方不解释其类型, 故对外类型为 [Any]。
 */
internal expect fun currentThreadMarker(): Any

/**
 * 挂起当前线程 [nanos] 纳秒, 或提前被 [unparkThread] 唤醒 (两端行为一致)。
 */
internal expect fun parkThread(nanos: Long)

/**
 * 唤醒 [currentThreadMarker] 标记的线程 (marker 类型不匹配时静默忽略)。
 */
internal expect fun unparkThread(marker: Any?)
