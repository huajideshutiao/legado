package io.legado.app.utils.concurrent

/**
 * KMP 同步 List 工厂 (替代 `java.util.Collections.synchronizedList(list)`)。
 *
 * 背景: commonMain 多处用 `Collections.synchronizedList(arrayListOf<T>())`
 * 保证跨线程读写安全 (如 ChangeCoverViewModelShared.searchBooks / ChangeBookSourceViewModelShared.searchBooks
 * 在 callbackFlow 内由多协程并发 add/clear/iterate), 但 `java.util.Collections` 在
 * Kotlin/Native target 不可用, 阻塞 iOS/鸿蒙编译, 故抽出 expect/actual
 * (与 [newConcurrentMap] / [newConcurrentSet] 同模式)。
 *
 * 各平台 actual 实现策略: 两端**都是真同步包装**:
 * - jvm/android: 直接委托 `java.util.Collections.synchronizedList(delegate)`,
 *   返回的 List 所有方法都 synchronized。
 * - iOS/鸿蒙 (nativeMain): `java.util.Collections` 不可用, 改用 atomicfu
 *   [kotlinx.atomicfu.locks.SynchronizedObject] 手写同语义包装, **每个方法都进同一把实例锁**。
 *
 * 选型提示: List 本身是写多读少的累积结构 (add/clear + 遍历), 逐操作加锁可接受;
 * 但如需"读远多于写"的共享映射, 请改走 [io.legado.app.help.casUpdate] 的不可变快照。
 *
 * 历史注记: 旧文字写的"Kotlin/Native 单线程 STM, 直接返回原 delegate"已不成立
 * (IoDispatcher 的 native actual 是 Dispatchers.Default 线程池), 实现已改为加锁包装。
 *
 * @param T List 元素类型
 * @param delegate 被包装的可变 List (与 `Collections.synchronizedList` 签名一致)
 * @return 同步包装后的可变 List (jvm/android 由 Collections 包装, native 由本仓库手写包装)
 */
expect fun <T> newSynchronizedList(delegate: MutableList<T>): MutableList<T>
