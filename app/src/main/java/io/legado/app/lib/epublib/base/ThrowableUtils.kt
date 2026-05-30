package io.legado.app.lib.epublib.base

import java.io.IOException

object ThrowableUtils {
    @Throws(IOException::class)
    fun rethrowAsIOException(throwable: Throwable): IOException {
        val newException = IOException(throwable.message, throwable)
        throw newException
    }
}
