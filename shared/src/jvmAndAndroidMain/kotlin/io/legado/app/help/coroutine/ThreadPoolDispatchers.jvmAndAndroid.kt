package io.legado.app.help.coroutine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * JVM/Android actual: 用 [Executors.newFixedThreadPool] 创建真实线程池。
 *
 * 与 app 端 MainViewModel.upTocPool 行为一致 (固定大小线程池 + asCoroutineDispatcher)。
 * 返回的 dispatcher 需在销毁时调用 [closeIfCloseable] 释放线程资源
 * (对照 app 端 `upTocPool.close()`)。
 */
actual fun newFixedThreadPoolDispatcher(size: Int): CoroutineDispatcher =
    Executors.newFixedThreadPool(size).asCoroutineDispatcher()

/**
 * JVM/Android actual: 强转 [java.lang.AutoCloseable] 后 close()。
 *
 * ExecutorCoroutineDispatcher (由 asCoroutineDispatcher 返回) 实现了 AutoCloseable,
 * 真实释放线程池; 非 Closeable 的 dispatcher (理论不会由此 expect 创建) 安全跳过。
 */
actual fun CoroutineDispatcher.closeIfCloseable() {
    (this as? java.lang.AutoCloseable)?.close()
}
