package io.legado.app.utils

import okio.IOException

/**
 * Throwable 纯 Kotlin 扩展（commonMain）。
 *
 * - [stackTraceStr] 走 [Throwable.stackTraceToString] / [Throwable.message],
 *   两者均为 common 期望成员, JVM actual 委托 java.lang.Throwable, 行为与原 JVM 实现一致。
 * - [asIOException] 走 [okio.IOException] (okhttp/okio 3.x KMP 库 commonMain expect class,
 *   JVM actual = java.io.IOException), 用构造函数 `IOException(message, cause)` 替代
 *   原 `initCause` (java.lang.Throwable 专属, commonMain 不可见), 行为等价。
 *
 * 包名/函数签名不变, 消费方 import 零改动。
 */
val Throwable.stackTraceStr: String
    get() {
        val stackTrace = stackTraceToString()
        val lMsg = this.message ?: "noErrorMsg"
        return when {
            stackTrace.isNotEmpty() -> stackTrace
            else -> lMsg
        }
    }

/**
 * 将当前 Throwable 包装为 IOException。
 *
 * 原实现走 `IOException(message)` + `initCause(this)` (java.lang.Throwable 专属);
 * commonMain 端用构造函数 `IOException(message, cause)` 等价替换。
 *
 * 返回类型 [okio.IOException] 在 JVM 平台 actual 为 java.io.IOException,
 * 消费方 (AbsCallBack.onError / PfdHelper throw 等) 接收 java.io.IOException, 类型兼容零改动。
 */
fun Throwable.asIOException(): IOException = IOException(this.message, this)
