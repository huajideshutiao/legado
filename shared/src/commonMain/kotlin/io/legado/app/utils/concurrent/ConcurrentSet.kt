package io.legado.app.utils.concurrent

/**
 * KMP 并发 Set 工厂 (替代 `java.util.concurrent.ConcurrentHashMap.newKeySet()`)。
 *
 * 背景: `modules/shared/.../SearchViewModel.kt` 原 `bookshelf` 集合用
 * `ConcurrentHashMap.newKeySet()` 创建线程安全 Set, 用于跨线程读写书架 key。
 * `java.util.concurrent.ConcurrentHashMap` 在 Kotlin/Native iOS target 不可用,
 * 阻塞 iOS 编译, 故抽出 expect/actual。
 *
 * 各平台 actual 实现策略:
 * - jvm/android: 仍用 `ConcurrentHashMap.newKeySet()`, 保持线程安全 (与原实现完全一致)。
 * - iOS/鸿蒙: Kotlin/Native 单线程 STM, `Collections.synchronizedSet` 不可用,
 *   直接返回 `mutableSetOf()` (Kotlin/Native 默认线程安全模型)。
 *
 * 注意: 在 Kotlin/Native 上, 跨线程访问 mutableSetOf 仍需手动同步
 * (推荐 kotlinx.atomicfu)。此处仅作为编译期兼容,
 * 调用方应避免在 Kotlin/Native 上跨线程访问。
 *
 * @param T Set 元素类型
 * @return 平台适当的可变 Set
 */
expect fun <T> newConcurrentSet(): MutableSet<T>
