package io.legado.app.help.coroutine

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

private var isDebuggable: Boolean = false

/** 安卓 = 主线程调度器（kotlinx-coroutines-android 提供），与下沉前行为一致。 */
internal actual val mainDispatcher: CoroutineDispatcher get() = Dispatchers.Main

/**
 * Android-KMP library 不生成 BuildConfig，由宿主在启动时注入可调试状态。
 * 使用 ApplicationInfo 标志而不是 app BuildConfig，避免共享模块反向依赖宿主生成类。
 */
fun registerAndroidDebugState(context: Context) {
    isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

/** 原 app 侧行为：仅可调试包打印堆栈。 */
actual fun Throwable.printStackTraceOnDebug() {
    if (isDebuggable) {
        printStackTrace()
    }
}
