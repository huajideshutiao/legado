package io.legado.app.utils

import java.net.ConnectException
import java.net.SocketTimeoutException

/** 对应原 HttpReadAloudService 的 `is SocketTimeoutException, is ConnectException` 判断。 */
actual fun isRetryableNetworkError(e: Throwable): Boolean {
    return e is SocketTimeoutException || e is ConnectException
}
