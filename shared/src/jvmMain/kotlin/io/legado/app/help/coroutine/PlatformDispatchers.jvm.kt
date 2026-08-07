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

private var isDebug: Boolean = false

/**
 * 桌面 JVM 无 BuildConfig, 由宿主 (desktop Main) 启动时注入调试状态,
 * 与 android 的 [registerAndroidDebugState] 同模式; 未注入时默认不打栈。
 */
fun registerJvmDebugState(debug: Boolean) {
    isDebug = debug
}

/** 对齐 Android BuildConfig.DEBUG 语义: 仅 debug 场景打栈 (开发期 run 任务注入 true, 打包产物不打)。 */
actual fun Throwable.printStackTraceOnDebug() {
    if (isDebug) {
        printStackTrace()
    }
}
