package io.legado.app.utils

/**
 * 判断异常是否为"超时 / 连接失败"这类可重试的网络错误。
 *
 * 对应 app 端原 `HttpReadAloudService` 里 `is SocketTimeoutException, is ConnectException` 的判断,
 * 用于 TTS 下载熔断计数。commonMain 不能引用 java.net, 故抽为 expect。
 */
expect fun isRetryableNetworkError(e: Throwable): Boolean
