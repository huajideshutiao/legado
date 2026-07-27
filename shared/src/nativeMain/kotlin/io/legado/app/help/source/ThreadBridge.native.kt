package io.legado.app.help.source

import io.legado.app.utils.platformSleep

/**
 * [currentThreadMarker] / [parkThread] / [unparkThread] 的 Native actual (iOS / 鸿蒙共用)。
 *
 * Kotlin/Native 无 `LockSupport` 等价的线程 park/unpark API, 降级为:
 * - [currentThreadMarker] 返回 [Unit] 占位 (Native 端无 `Thread` 概念,
 *   验证码流程需 Native UI provider 配合, 此处仅保证编译通过)
 * - [parkThread] 委托 [platformSleep] 阻塞 (不可被 unpark 提前唤醒,
 *   但 [SourceVerificationHelpShared.waitVerificationResult] 的轮询循环
 *   会在 sleep 超时后重新检查 getResult, 结果到达即退出, 行为等价)
 * - [unparkThread] 空操作 (靠轮询超时唤醒)
 *
 * 注: 真正的 Native 端验证码流程需协程化改造 (suspend + delay), 超出本下沉任务范围;
 * 此处保证 commonMain 核心流程可编译复用, 行为在结果到达后至多多等一个 waitTime 周期。
 *
 * 下沉说明: 原 iosMain/ohosMain 各有一份逐字节相同的 actual 实现, 通过 nativeMain 中间源集共用,
 * 消除平台 actual 直接拷贝。`platformSleep` 为 commonMain expect fun, actual 由各 Native target
 * (iosMain/ohosMain 的 PlatformSleep) 提供, nativeMain dependsOn commonMain 可直接调用 expect 声明。
 */
internal actual fun currentThreadMarker(): Any = Unit

internal actual fun parkThread(nanos: Long) {
    platformSleep(nanos / 1_000_000)
}

internal actual fun unparkThread(marker: Any?) {
    // Native 无 unpark 概念, 靠轮询超时唤醒
}
