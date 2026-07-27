package io.legado.app.help.coroutine

import kotlin.coroutines.CoroutineContext

/**
 * runBlocking 的 commonMain expect 门面。
 *
 * kotlinx.coroutines.runBlocking 是 JVM-only API (依赖事件循环实现),
 * commonMain 不可见。AnalyzeUrlCore 下沉 commonMain 后, 同步版方法
 * (getStrResponse/getResponse/getByteArray/getInputStream) 仍需阻塞语义,
 * 经本 expect 委托 jvmAndAndroidMain actual 调用原 runBlocking, 行为不变。
 *
 * 注: 这些同步方法属于 KSP @JsApi 分派表 (通过 AnalyzeUrl 继承链),
 * 方法签名 (返回类型/参数) 必须零 diff, 故不能改成 suspend。
 */
internal expect fun <T> runBlockingInScope(context: CoroutineContext, block: suspend () -> T): T
