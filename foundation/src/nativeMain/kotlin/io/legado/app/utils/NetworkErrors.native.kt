package io.legado.app.utils

/**
 * Native (iOS / 鸿蒙) 侧网络超时/连接错误判断。
 *
 * nativeMain 无 ktor-client 依赖 (仅 iosMain 有), 故按类名匹配 Ktor 的
 * HttpRequestTimeoutException / ConnectTimeoutException / SocketTimeoutException,
 * 并对含 timeout/connect 关键字的 IO 异常兜底。
 */
actual fun isRetryableNetworkError(e: Throwable): Boolean {
    val name = e::class.qualifiedName ?: e::class.simpleName ?: return false
    if (name.endsWith("HttpRequestTimeoutException") ||
        name.endsWith("ConnectTimeoutException") ||
        name.endsWith("SocketTimeoutException") ||
        name.endsWith("ConnectException")
    ) {
        return true
    }
    val message = e.message?.lowercase() ?: return false
    return message.contains("timeout") || message.contains("connection refused")
}
