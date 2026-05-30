package io.legado.app.lib.epublib.util

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader

object IOUtil {
    const val EOF: Int = -1
    const val DEFAULT_BUFFER_SIZE: Int = DEFAULT_BUFFER_SIZE_BYTES

    @Throws(IOException::class)
    fun toByteArray(input: Reader, encoding: String): ByteArray =
        input.readText().toByteArray(charset(encoding))

    @Throws(IOException::class)
    fun toByteArray(input: InputStream?): ByteArray =
        input?.readBytes() ?: ByteArray(0)

    @Throws(IOException::class)
    fun toByteArray(input: InputStream?, size: Int): ByteArray? = try {
        input?.readBytes()
    } catch (_: OutOfMemoryError) {
        null
    }

    @Throws(IOException::class)
    fun copy(input: InputStream?, output: OutputStream): Long =
        input?.copyTo(output) ?: 0

    fun length(array: ByteArray?): Int = array?.size ?: 0

    @Throws(IOException::class)
    fun close(closeable: Closeable?, consumer: ((IOException?) -> Unit)?) {
        if (closeable != null) {
            try {
                closeable.close()
            } catch (e: IOException) {
                consumer?.invoke(e)
            }
        }
    }
}

private const val DEFAULT_BUFFER_SIZE_BYTES = 8 * 1024
