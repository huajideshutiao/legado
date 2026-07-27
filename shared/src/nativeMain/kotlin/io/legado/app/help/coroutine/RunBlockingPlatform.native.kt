package io.legado.app.help.coroutine

import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

/**
 * runBlocking 的 iOS/鸿蒙 actual 实现。
 *
 * 详见 commonMain/help/coroutine/RunBlockingPlatform.kt expect 注释。
 * 直接委托 kotlinx.coroutines.runBlocking (KMP 标准, commonMain 已有),
 * 行为与 jvmAndAndroidMain 一致 (阻塞当前线程, 等待协程完成)。
 *
 * 注: Kotlin/Native 中 runBlocking 在主线程调用可能造成 deadlock (与 JVM 行为不同),
 * 调用方均为后台阻塞路径 (AnalyzeUrlCore 同步版方法), 与 JVM 行为对齐。
 */
actual fun <T> runBlockingInScope(context: CoroutineContext, block: suspend () -> T): T =
    runBlocking(context) { block() }
