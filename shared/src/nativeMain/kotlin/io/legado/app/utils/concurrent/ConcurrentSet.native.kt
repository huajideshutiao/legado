package io.legado.app.utils.concurrent

/**
 * `newConcurrentSet` 的 iOS/鸿蒙 actual 实现 (nativeMain 中间源集共用)。
 *
 * Kotlin/Native iOS/鸿蒙 target 无 `java.util.concurrent.ConcurrentHashMap`,
 * 直接返回 `mutableSetOf()`。
 *
 * 注意: Kotlin/Native 默认线程安全模型下, 跨线程访问 mutableSetOf 仍需手动同步
 * (推荐 kotlinx.atomicfu)。SearchViewModel.bookshelf 在 iOS/鸿蒙上目前仅由
 * 单一协程 scope 串行访问 (Dispatchers.Default 在 Kotlin/Native 上为单线程调度),
 * 此处仅作为编译期兼容。
 *
 * 详见 commonMain/utils/concurrent/ConcurrentSet.kt expect 注释。
 * iOS/鸿蒙两端 actual 实现完全一致, 下沉到 nativeMain 共用。
 */
actual fun <T> newConcurrentSet(): MutableSet<T> = mutableSetOf()
