package org.jsoup.internal

import org.jsoup.Connection
import java.io.InputStream

/** [Connection.KeyVal] 的简单可变实现,用于表单字段或文件上传 */
class HttpKeyVal private constructor(
    private var key: String,
    private var value: String,
    private var inputStream: InputStream? = null,
    private var contentType: String? = null,
    private var filename: String? = null,
) : Connection.KeyVal {

    override fun key(): String = key
    override fun key(key: String): HttpKeyVal = apply { this.key = key }
    override fun value(): String = value
    override fun value(value: String): HttpKeyVal = apply { this.value = value }

    override fun inputStream(): InputStream? = inputStream
    override fun inputStream(inputStream: InputStream): HttpKeyVal = apply {
        this.inputStream = inputStream
    }

    override fun hasInputStream(): Boolean = inputStream != null

    /** 文件名(若有) */
    fun filename(): String? = filename
    fun contentType(): String? = contentType

    companion object {
        @JvmStatic
        fun create(key: String, value: String): HttpKeyVal = HttpKeyVal(key, value)

        @JvmStatic
        fun create(key: String, filename: String, inputStream: InputStream): HttpKeyVal =
            HttpKeyVal(key, filename, inputStream, null, filename)

        @JvmStatic
        fun create(
            key: String, filename: String, inputStream: InputStream, contentType: String?
        ): HttpKeyVal = HttpKeyVal(key, filename, inputStream, contentType, filename)
    }
}
