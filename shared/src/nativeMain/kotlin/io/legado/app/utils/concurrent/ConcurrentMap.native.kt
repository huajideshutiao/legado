package io.legado.app.utils.concurrent

/**
 * `newConcurrentMap` 的 iOS/鸿蒙 actual 实现 (nativeMain 中间源集共用)。
 *
 * Kotlin/Native iOS/鸿蒙 target 无 `java.util.concurrent.ConcurrentHashMap`,
 * 直接返回 `mutableMapOf()`。
 *
 * 注意: Kotlin/Native 默认线程安全模型下, 跨线程访问 mutableMapOf 仍需手动同步
 * (推荐 kotlinx.atomicfu)。此处仅作为编译期兼容。
 *
 * 详见 commonMain/utils/concurrent/ConcurrentMap.kt expect 注释。
 * iOS/鸿蒙两端 actual 实现完全一致, 下沉到 nativeMain 共用。
 */
actual fun <K, V> newConcurrentMap(): MutableMap<K, V> = mutableMapOf()
