package io.legado.app.help.coroutine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 桌面 JVM 无内建 Main 调度器（kotlinx-coroutines-core 的 Dispatchers.Main 需 UI 库注入，
 * 缺失时访问即抛 IllegalStateException）。桌面属工具/测试靶，回调无需 UI 线程亲和，
 * 故 fallback Dispatchers.Default，保证下沉件在 jvm 目标可跑（附录 J：桌面=JVM 验证靶）。
 */
internal actual val mainDispatcher: CoroutineDispatcher get() = Dispatchers.Default

/** 桌面无 BuildConfig，工具场景默认打栈便于诊断。 */
actual fun Throwable.printOnDebug() {
    printStackTrace()
}
