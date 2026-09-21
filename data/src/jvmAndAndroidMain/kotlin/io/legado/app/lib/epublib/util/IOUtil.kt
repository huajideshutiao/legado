package io.legado.app.lib.epublib.util

object IOUtil {
    const val EOF: Int = -1
    const val DEFAULT_BUFFER_SIZE: Int = DEFAULT_BUFFER_SIZE_BYTES

    fun length(array: ByteArray?): Int = array?.size ?: 0
}

private const val DEFAULT_BUFFER_SIZE_BYTES = 8 * 1024
