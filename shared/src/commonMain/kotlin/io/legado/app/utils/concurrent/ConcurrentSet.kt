package io.legado.app.utils.concurrent

/**
 * KMP 并发 Set 工厂 (替代 `java.util.concurrent.ConcurrentHashMap.newKeySet()`)。
 *
 * 背景: `modules/shared/.../SearchViewModel.kt` 原 `bookshelf` 集合用
 * `ConcurrentHashMap.newKeySet()` 创建线程安全 Set, 用于跨线程读写书架 key。
 * `java.util.concurrent.ConcurrentHashMap` 在 Kotlin/Native iOS target 不可用,
 * 阻塞 iOS 编译, 故抽出 expect/actual。
 *
 * 各平台 actual 实现策略: 两端**都是真线程安全**:
 * - jvm/android: 直接委托 `ConcurrentHashMap.newKeySet()`。
 * - iOS/鸿蒙 (nativeMain): 用 atomicfu [kotlinx.atomicfu.locks.SynchronizedObject] 包一层,
 *   **每个操作 (含 contains/size/iterator) 都进同一把实例锁**; 迭代面返回快照副本。
 *
 * 历史注记: 本段旧文字曾写"Kotlin/Native 单线程 STM, 直接返回 mutableSetOf()"。
 * 该前提已不成立 (IoDispatcher 的 native actual 是 Dispatchers.Default 线程池),
 * 实现已改为加锁包装; 本工厂是"每读一锁", 读密的热路径请改走
 * [io.legado.app.help.casUpdate] 的不可变快照。
 *
 * @param T Set 元素类型
 * @return 平台适当的可变 Set
 */
expect fun <T> newConcurrentSet(): MutableSet<T>
