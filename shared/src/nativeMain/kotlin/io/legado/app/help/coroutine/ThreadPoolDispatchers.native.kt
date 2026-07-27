package io.legado.app.help.coroutine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Native (iOS/鸿蒙) actual: 用 [Dispatchers.Default] 兜底。
 *
 * Kotlin/Native 无 java.util.concurrent.Executors, 且 Native 端 UpdateBookShared 当前
 * 通过 NativeServiceLauncher 调用 (stub 调度, 实际并发由 CoroutineScope 控制)。
 * Dispatchers.Default 不可 close, 调用方 [closeIfCloseable] 行为安全 (no-op)。
 *
 * 后续 Native 端真实化时, 可改为基于平台线程 API (iOS dispatch_queue / 鸿蒙 TaskPool)
 * 的自定义 dispatcher, 但需保持 close() 语义。
 */
actual fun newFixedThreadPoolDispatcher(size: Int): CoroutineDispatcher = Dispatchers.Default

/**
 * Native actual: no-op。
 *
 * Dispatchers.Default 不可 close 也无需 close (由运行时管理生命周期)。
 */
actual fun CoroutineDispatcher.closeIfCloseable() {
    // no-op: Dispatchers.Default 无需也无可释放资源
}
