package io.legado.app.help.coroutine

import kotlinx.coroutines.CancellationException

actual class ActivelyCancelException : CancellationException() {

    override fun fillInStackTrace(): Throwable {
        stackTrace = emptyArray()
        return this
    }

}
