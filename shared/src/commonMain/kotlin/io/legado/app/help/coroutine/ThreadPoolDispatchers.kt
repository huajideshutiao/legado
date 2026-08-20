package io.legado.app.help.coroutine

import kotlinx.coroutines.CoroutineDispatcher

/**
 * 创建固定大小的线程池调度器 (expect, 各平台 actual 提供)。
 *
 * # 背景
 * commonMain 不能直接用 `java.util.concurrent.Executors.newFixedThreadPool(size).asCoroutineDispatcher()`
 * (JVM 专属 API)。UpdateBookShared 等下沉件需要限流线程池 (对照 app 端 upTocPool), 故抽 expect/actual。
 *
 * # 各端 actual 实现
 * - **jvmAndAndroidMain**: `Executors.newFixedThreadPool(size).asCoroutineDispatcher()`
 *   (与 app 端 MainViewModel.upTocPool 一致, 真实线程池, 大小可控)
 * - **nativeMain** (iOS/鸿蒙): `Dispatchers.IO.limitedParallelism(size)`
 *   (Kotlin/Native 无 java.util.concurrent, 用受限并行度视图保住限流语义)
 *
 * # 关闭
 * 返回的 [CoroutineDispatcher] 在 JVM 端是 ExecutorCoroutineDispatcher, 调用方需在
 * 销毁时调用 [closeIfCloseable] 释放线程资源 (对照 app 端 `upTocPool.close()`)。
 * Native 端 limitedParallelism 视图不可 close, [closeIfCloseable] 为 no-op, 行为安全。
 *
 * @param size 线程池大小 (调用方应限制上限, 如 `min(threadCount, AppConst.MAX_THREAD)`)
 */
expect fun newFixedThreadPoolDispatcher(size: Int): CoroutineDispatcher

/**
 * IO 调度器门面 (expect, 各端 actual 均为 `Dispatchers.IO`)。
 *
 * `Dispatchers.IO` 定义在 kotlinx-coroutines 的 concurrent 源集, commonMain
 * metadata 编译不可见 (平台编译可见), 故经 expect 转发供 commonMain 使用。
 */
expect val IoDispatcher: CoroutineDispatcher

/**
 * 安全关闭 [CoroutineDispatcher] (expect, 各平台 actual 提供)。
 *
 * # 背景
 * commonMain 中 [CoroutineDispatcher] 无 `close()` 方法 (仅 JVM 端
 * ExecutorCoroutineDispatcher 实现了 Closeable)。UpdateBookShared 等下沉件在
 * commonMain 持有 [newFixedThreadPoolDispatcher] 返回的 dispatcher 并需在销毁时关闭,
 * 故抽 expect/actual 统一关闭入口。
 *
 * # 各端 actual 实现
 * - **jvmAndAndroidMain**: 强转 `java.lang.AutoCloseable` 后 close()
 *   (ExecutorCoroutineDispatcher 实现了 AutoCloseable, 真实释放线程池)
 * - **nativeMain**: no-op (Dispatchers.Default 不可 close, 也无需 close)
 *
 * 调用方无需判空, 非 Closeable 的 dispatcher 调用本方法安全无副作用。
 */
expect fun CoroutineDispatcher.closeIfCloseable()
