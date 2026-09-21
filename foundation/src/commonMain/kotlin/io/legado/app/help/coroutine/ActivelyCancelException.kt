package io.legado.app.help.coroutine

import kotlinx.coroutines.CancellationException

// expect/actual: fillInStackTrace 抑制栈捕获是 JVM-only override (取消是高频路径), native actual 为普通类。
// 父类用 kotlinx.coroutines.CancellationException: common metadata 中它与
// kotlin.coroutines.cancellation 版是不同符号 (各平台 actual 同为一个类), Job.cancel(cause) 需要前者。
expect class ActivelyCancelException() : CancellationException
