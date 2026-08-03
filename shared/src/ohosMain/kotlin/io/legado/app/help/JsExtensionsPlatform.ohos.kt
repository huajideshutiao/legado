@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.rawValue
import platform.posix.pthread_self

/**
 * 鸿蒙主线程判定: 启动期 (主线程) 捕获 `pthread_self()` 后逐次比对。
 *
 * 鸿蒙无 NSThread 等价 API (kotlin.native.Platform 也无 isMainThread, 已核实 2.3.20 stdlib),
 * napi 桥是异步 tsfn 不适合同步判定; pthread_t 在 musl 上是 `unsigned long`,
 * cinterop 映射为 CPointer, 取 rawValue 按整型比较。未捕获时
 * (registerOhosMainThread 未调) 保守返回 false。
 */
private var mainThreadId: ULong? = null

/**
 * 宿主启动早期 (EntryAbility.onCreate, 主线程) 调用一次, 记录主线程 pthread id。
 * 必须在任何 JsExtensionsCommon.webView* 调用之前 (JS eval 可能发生在工作线程)。
 */
fun registerOhosMainThread() {
    mainThreadId = pthread_self()?.rawValue
}

internal actual fun isMainThreadPlatform(): Boolean {
    val id = mainThreadId ?: return false
    return pthread_self()?.rawValue == id
}
