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
 * 各平台 actual 实现策略:
 * - jvm/android: 仍用 `java.util.Collections.synchronizedList(delegate)`,
 *   保持线程安全语义与原实现完全一致 (返回的 List 所有方法都 synchronized)。
 * - iOS/鸿蒙: Kotlin/Native 单线程 STM, `Collections.synchronizedList` 不可用,
 *   直接返回原 delegate (Kotlin/Native 默认线程安全模型)。
 *
 * 注意: 在 Kotlin/Native 上, 返回的 List 跨线程访问仍需手动同步
 * (推荐 kotlinx.atomicfu)。此处仅作为编译期兼容,
 * 调用方应避免在 Kotlin/Native 上跨线程访问, 或用 SynchronizedObject 包裹。
 *
 * @param T List 元素类型
 * @param delegate 被包装的可变 List (与 `Collections.synchronizedList` 签名一致)
 * @return 平台适当的可变 List (jvm/android 为同步包装, native 为原 delegate)
 */
expect fun <T> newSynchronizedList(delegate: MutableList<T>): MutableList<T>
