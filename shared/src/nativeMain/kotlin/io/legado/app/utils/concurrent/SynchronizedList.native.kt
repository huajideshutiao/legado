package io.legado.app.utils.concurrent

/**
 * `newSynchronizedList` 的 iOS/鸿蒙 actual 实现 (nativeMain 中间源集共用)。
 *
 * Kotlin/Native iOS/鸿蒙 target 无 `java.util.Collections.synchronizedList`,
 * 直接返回原 delegate (Kotlin/Native 默认线程安全模型)。
 *
 * 注意: Kotlin/Native 默认线程安全模型下, 跨线程访问 delegate 仍需手动同步
 * (推荐 kotlinx.atomicfu)。ChangeCoverViewModelShared.searchBooks 在 iOS/鸿蒙上
 * 目前仅由单一 callbackFlow + search 协程访问, 此处仅作为编译期兼容。
 *
 * 详见 commonMain/utils/concurrent/SynchronizedList.kt expect 注释。
 * iOS/鸿蒙两端 actual 实现完全一致, 下沉到 nativeMain 共用。
 */
actual fun <T> newSynchronizedList(delegate: MutableList<T>): MutableList<T> = delegate
