package io.legado.app.help.coroutine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Coroutine 链式协程默认回调调度器的 iOS/鸿蒙 actual。
 *
 * 详见 commonMain/help/coroutine/PlatformDispatchers.kt expect 注释。
 * - mainDispatcher: 用 Dispatchers.Main (kotlinx-coroutines-core KMP 提供, iOS/鸿蒙均支持)
 * - printStackTraceOnDebug: 无 BuildConfig.DEBUG 等价物且无宿主注入点, 直接 printStackTrace。
 *
 * 注: iOS/鸿蒙保持无条件打栈便于诊断; 与 jvmMain (经 registerJvmDebugState 注入, 仅 debug 打栈)
 * 行为不同, 属已知差异。
 */
internal actual val mainDispatcher: CoroutineDispatcher get() = Dispatchers.Main

actual fun Throwable.printStackTraceOnDebug() {
    printStackTrace()
}
