package io.legado.app.exception

actual open class NoStackTraceException actual constructor(msg: String) : Exception(msg) {

    override fun fillInStackTrace(): Throwable {
        stackTrace = emptyStackTrace
        return this
    }

    companion object {
        private val emptyStackTrace = emptyArray<StackTraceElement>()
    }

}
