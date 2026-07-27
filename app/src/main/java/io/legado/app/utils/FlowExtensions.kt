package io.legado.app.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.appDb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.produceIn

/*
 * Flow 扩展 app 端剩余区。
 *
 * 原 FlowExtensions.kt 中的纯协程扩展 (onEachParallel/mapParallel/mapParallelSafe/
 * onEachIndexed/mapIndexed/mapAsync/mapAsyncIndexed) 已下沉到 shared
 * FlowExtensionsShared.kt (供 BookChapterList/BookContent 使用), 本文件仅保留
 * 依赖 androidx.lifecycle + appDb 的 flowWithLifecycleAndDatabaseChange*。
 *
 * 跨模块同包名同签名扩展自动合并, 消费方 import 零改动。
 */

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.flowWithLifecycleAndDatabaseChange(
    lifecycle: Lifecycle,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    table: String
): Flow<T> = callbackFlow {
    var update = 0
    val channel = appDb.invalidationTracker
        .createFlow(table)
        .conflate()
        .onEach { update++ }
        .produceIn(this)
    lifecycle.repeatOnLifecycle(minActiveState) {
        if (update == 0) {
            channel.receive()
        }
        this@flowWithLifecycleAndDatabaseChange.collect {
            update = 0
            send(it)
        }
    }
    close()
}

fun <T> Flow<T>.flowWithLifecycleAndDatabaseChangeFirst(
    lifecycle: Lifecycle,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    table: String
): Flow<T> = callbackFlow {
    var update = 0
    val isActive = lifecycle.currentState.isAtLeast(minActiveState)
    val channel = appDb.invalidationTracker
        .createFlow(table, emitInitialState = isActive)
        .conflate()
        .onEach { update++ }
        .produceIn(this)
    if (!isActive) {
        firstOrNull()?.let {
            send(it)
        }
    }
    lifecycle.repeatOnLifecycle(minActiveState) {
        if (update == 0) {
            channel.receive()
        }
        this@flowWithLifecycleAndDatabaseChangeFirst.collect {
            update = 0
            send(it)
        }
    }
    close()
}
