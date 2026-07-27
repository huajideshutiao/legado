package io.legado.app.utils.concurrent

/**
 * KMP 并发 Map 工厂 (替代 `java.util.concurrent.ConcurrentHashMap<K,V>()`)。
 *
 * 背景: commonMain 多处用 `ConcurrentHashMap` 保证跨线程读写安全,
 * 但 `java.util.concurrent.ConcurrentHashMap` 在 Kotlin/Native target 不可用,
 * 阻塞 iOS/鸿蒙编译, 故抽出 expect/actual (与 [newConcurrentSet] 同模式)。
 *
 * 各平台 actual 实现策略:
 * - jvm/android: 仍用 `ConcurrentHashMap`, 保持线程安全 (与原实现完全一致)。
 * - iOS/鸿蒙: Kotlin/Native 单线程 STM, 返回 `mutableMapOf()`
 *   (Kotlin/Native 默认线程安全模型, 跨线程访问需手动同步, 详见 [newConcurrentSet] 注释)。
 *
 * 注意: 在 Kotlin/Native 上, 跨线程访问 mutableMapOf 仍需手动同步
 * (推荐 kotlinx.atomicfu)。此处仅作为编译期兼容,
 * 调用方应避免在 Kotlin/Native 上跨线程访问, 或用 SynchronizedObject 包裹。
 *
 * @param K Map 键类型
 * @param V Map 值类型
 * @return 平台适当的可变 Map
 */
expect fun <K, V> newConcurrentMap(): MutableMap<K, V>
