package io.legado.app.help

import io.legado.app.constant.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

// 下沉自 app 模块 io.legado.app.help.DispatchersMonitor。
// 下沉原因: 本 object 仅依赖 kotlinx.coroutines + java.util.concurrent.Executors,
// 无任何 Android 平台依赖, 属于纯 JVM/Android 共用逻辑, 落 jvmAndAndroidMain 可在
// app 与 desktop/服务端复用。日志落盘走已下沉 commonMain 的 AppLog.put(String);
// 原 AppConfig.recordLog 门经 AppLog.isRecordLogEnabled 查询 (host provider 间接,
// 行为等价, 未注册 host 时返回 false)。
// 包名/对象名/方法签名/实现逻辑保持与 app 端原版一致, 仅替换日志与配置调用。
object DispatchersMonitor {

    private const val TAG = "DispatchersMonitor"

    private val dispatcher by lazy {
        Executors.newSingleThreadExecutor {
            Thread(it, TAG)
        }.asCoroutineDispatcher()
    }

    private val scope = CoroutineScope(dispatcher)

    fun init() {
        scope.coroutineContext.cancelChildren()
        if (!AppLog.isRecordLogEnabled) {
            return
        }
        monitor(IO)
        monitor(Default)
        monitor(Main)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun monitor(dispatcher: CoroutineDispatcher) {
        scope.launch {
            while (isActive) select {
                launch {
                    withContext(dispatcher) {
                        delay(3000)
                    }
                }.onJoin {}
                onTimeout(5000) {
                    AppLog.put("Dispatcher $dispatcher is timed out waiting for for 5000ms.")
                }
            }
        }
    }

}
