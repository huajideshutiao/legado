package io.legado.app.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Semaphore

/**
 * Flow 纯协程扩展下沉区 (shared jvmAndAndroidMain)。
 *
 * 原 app 端 FlowExtensions.kt 拆分: 依赖 androidx.lifecycle / appDb 的
 * flowWithLifecycleAndDatabaseChange* 仍留 app 端, 其余纯 kotlinx.coroutines
 * 扩展下沉到本文件, 供 shared 模块 (BookChapterList/BookContent 等) 使用。
 *
 * 包名/函数签名不变, 消费方 import 零改动 (跨模块同包名同签名扩展自动合并,
 * 但不允许重复定义, 需从 app 端 FlowExtensions.kt 删除已下沉的扩展)。
 */

@OptIn(ExperimentalCoroutinesApi::class)
inline fun <T> Flow<T>.onEachParallel(
    concurrency: Int,
    crossinline action: suspend (T) -> Unit
): Flow<T> = flatMapMerge(concurrency) { value ->
    flow {
        action(value)
        emit(value)
    }
}.buffer(0)

@OptIn(ExperimentalCoroutinesApi::class)
inline fun <T, R> Flow<T>.mapParallel(
    concurrency: Int,
    crossinline transform: suspend (T) -> R,
): Flow<R> = flatMapMerge(concurrency) { value -> flow { emit(transform(value)) } }.buffer(0)


@OptIn(ExperimentalCoroutinesApi::class)
inline fun <T, R> Flow<T>.mapParallelSafe(
    concurrency: Int,
    size: Int,
    crossinline transform: suspend (T) -> R,
): Flow<R> = flatMapMerge(concurrency) { value ->
    flow {
        try {
            emit(transform(value))
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            if (size == 1) throw e
        }
    }
}.buffer(0)

inline fun <T> Flow<T>.onEachIndexed(
    crossinline action: suspend (index: Int, T) -> Unit,
): Flow<T> = flow {
    var index = 0
    collect { value ->
        action(index++, value)
        emit(value)
    }
}

inline fun <T, R> Flow<T>.mapIndexed(
    crossinline action: suspend (index: Int, T) -> R,
): Flow<R> = flow {
    var index = 0
    collect { value ->
        emit(action(index++, value))
    }
}

inline fun <T, R> Flow<T>.mapAsync(
    concurrency: Int,
    crossinline transform: suspend (T) -> R
): Flow<R> = if (concurrency == 1) {
    map { transform(it) }
} else {
    Semaphore(concurrency).let { semaphore ->
        channelFlow {
            collect {
                semaphore.acquire()
                send(async { transform(it) })
            }
        }.map {
            it.await()
        }.onEach { semaphore.release() }
    }.buffer(0)
}

inline fun <T, R> Flow<T>.mapAsyncIndexed(
    concurrency: Int,
    crossinline transform: suspend (index: Int, T) -> R
): Flow<R> = if (concurrency == 1) {
    mapIndexed { index, value ->
        transform(index, value)
    }
} else {
    Semaphore(concurrency).let { semaphore ->
        channelFlow {
            var index = 0
            collect {
                semaphore.acquire()
                val i = index++
                send(async { transform(i, it) })
            }
        }.map {
            it.await()
        }.onEach { semaphore.release() }
    }.buffer(0)
}
