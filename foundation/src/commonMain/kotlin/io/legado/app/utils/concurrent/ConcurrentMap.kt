package io.legado.app.utils.concurrent

/**
 * KMP 并发 Map 工厂 (替代 `java.util.concurrent.ConcurrentHashMap<K,V>()`)。
 *
 * 背景: commonMain 多处用 `ConcurrentHashMap` 保证跨线程读写安全,
 * 但 `java.util.concurrent.ConcurrentHashMap` 在 Kotlin/Native target 不可用,
 * 阻塞 iOS/鸿蒙编译, 故抽出 expect/actual (与 [newConcurrentSet] 同模式)。
 *
 * 各平台 actual 实现策略: 两端**都是真线程安全**, 差别只在成本:
 * - jvm/android: 直接 `ConcurrentHashMap`, 读走无锁 volatile 桶定位。
 * - iOS/鸿蒙 (nativeMain): Kotlin/Native 无 `ConcurrentHashMap`, 用 atomicfu
 *   [kotlinx.atomicfu.locks.SynchronizedObject] 包一层, **每个操作 (含 get/containsKey/size)
 *   都进同一把实例锁**; keys/values/entries 返回快照副本, 避免边遍历边改抛并发修改异常。
 *
 * 选型提示: 本工厂适合"读写均衡 / 低频"的共享可变映射。若是**读远多于写**的热路径
 * (如图片/封面并发取源, 首屏几十次读同时涌入), native 端的"每读一锁"会把并行读串行化,
 * 而且可变映射要维护 LRU 访问序就必须"读时改表" —— 这种场景请改用不可变快照 + CAS 发布
 * (copy-on-write), 见 [io.legado.app.help.casUpdate]。
 *
 * 历史注记: 早先 native actual 确实返回过裸 `mutableMapOf()` (建立在"Kotlin/Native 单线程
 * 调度"的旧前提上); 该前提已不成立 (IoDispatcher 的 native actual 是 Dispatchers.Default
 * 线程池), 实现已改为加锁包装。本段旧描述曾误导调用方, 勿再照抄。
 *
 * @param K Map 键类型
 * @param V Map 值类型
 * @return 平台适当的可变 Map
 */
expect fun <K, V> newConcurrentMap(): MutableMap<K, V>
