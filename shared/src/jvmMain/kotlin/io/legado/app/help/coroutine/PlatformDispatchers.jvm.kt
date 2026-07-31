package io.legado.app.help.coroutine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.EmptyCoroutineContext

/**
 * JVM 的 Dispatchers.Main 需 UI 库 (kotlinx-coroutines-swing) 经 ServiceLoader 注入,
 * 缺失时该实例在 dispatch 时才抛异常, 故先探测一次再决定; 无 UI 库的纯 JVM 场景
 * (测试/工具) 回退 Dispatchers.Default, 保证下沉件可跑。
 */
@OptIn(InternalCoroutinesApi::class)
private val resolvedMainDispatcher: CoroutineDispatcher = runCatching {
    Dispatchers.Main.also { it.isDispatchNeeded(EmptyCoroutineContext) }
}.getOrDefault(Dispatchers.Default)

internal actual val mainDispatcher: CoroutineDispatcher get() = resolvedMainDispatcher

/** 桌面无 BuildConfig，工具场景默认打栈便于诊断。 */
actual fun Throwable.printStackTraceOnDebug() {
    printStackTrace()
}
