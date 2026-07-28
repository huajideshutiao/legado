package io.legado.app.help.coroutine

import io.legado.shared.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 安卓 = 主线程调度器（kotlinx-coroutines-android 提供），与下沉前行为一致。 */
internal actual val mainDispatcher: CoroutineDispatcher get() = Dispatchers.Main

/** 原 app 侧行为：仅 DEBUG 打栈。本模块 BuildConfig.DEBUG 随 app 构建类型一致。 */
actual fun Throwable.printStackTraceOnDebug() {
    if (BuildConfig.DEBUG) {
        printStackTrace()
    }
}
