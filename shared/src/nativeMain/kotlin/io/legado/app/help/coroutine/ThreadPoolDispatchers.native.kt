package io.legado.app.help.coroutine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Native (iOS/鸿蒙) actual: 用 [Dispatchers.Default] 兜底。
 *
 * Kotlin/Native 无 java.util.concurrent.Executors。size 入参被忽略:
 * 固定池大小的限流语义在 Native 端丢失, 并发上限由 Default dispatcher 自身线程数决定。
 * Dispatchers.Default 不可 close, 调用方 [closeIfCloseable] 行为安全 (no-op)。
 *
 * 如需真实限流, 可改为基于 [kotlinx.coroutines.CoroutineDispatcher.limitedParallelism]
 * 或平台线程 API 的自定义 dispatcher, 但需保持 close() 语义。
 */
actual fun newFixedThreadPoolDispatcher(size: Int): CoroutineDispatcher = Dispatchers.Default

/** Native actual: 直接转发 [Dispatchers.IO] (coroutines 1.7+ 在 Native 可用)。 */
actual val IoDispatcher: CoroutineDispatcher get() = Dispatchers.IO

/**
 * Native actual: no-op。
 *
 * Dispatchers.Default 不可 close 也无需 close (由运行时管理生命周期)。
 */
actual fun CoroutineDispatcher.closeIfCloseable() {
    // no-op: Dispatchers.Default 无需也无可释放资源
}
