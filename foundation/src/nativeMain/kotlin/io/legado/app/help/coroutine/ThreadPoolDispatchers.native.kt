package io.legado.app.help.coroutine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Native (iOS/鸿蒙) actual: [Dispatchers.IO] 的限流视图。
 *
 * Kotlin/Native 无 java.util.concurrent.Executors, 用 `limitedParallelism` 取得同样的
 * "最多 size 个并发" 语义 (与 commonMain SearchModel 的 searchPool 同款写法)。
 * 阻塞式网络/文件 IO 不再与 CPU 任务抢 Dispatchers.Default 的核数级线程。
 */
actual fun newFixedThreadPoolDispatcher(size: Int): CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(size)

/** Native actual: 使用 [Dispatchers.IO]，兼容 iOS 与 OHOS。 */
actual val IoDispatcher: CoroutineDispatcher get() = Dispatchers.IO

/**
 * Native actual: no-op。
 *
 * `limitedParallelism` 返回的是 Dispatchers.IO 的视图, 既不可 close 也无需 close;
 * 调用方 (UpdateBookShared / ChangeSource 等) 只经本函数关闭, 不会直接 close。
 */
actual fun CoroutineDispatcher.closeIfCloseable() {
    // no-op: Dispatchers.IO 的限流视图无需也无可释放资源
}
