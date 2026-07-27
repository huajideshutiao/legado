package io.legado.app.help.coroutine

import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

/**
 * runBlocking 的 jvmAndAndroidMain actual 实现。
 *
 * 详见 commonMain/help/coroutine/RunBlockingPlatform.kt expect 注释。
 * 直接委托 kotlinx.coroutines.runBlocking (JVM-only), 行为与原同步版方法一致。
 */
actual fun <T> runBlockingInScope(context: CoroutineContext, block: suspend () -> T): T =
    runBlocking(context) { block() }
