package io.legado.app.utils

import java.nio.charset.Charset

actual object Base64Lenient {

    actual fun decode(source: String): ByteArray = Base64LenientCore.decode(source)

    actual fun decodeStr(source: String?): String? = Base64LenientCore.decodeStr(source)

    /** 非 UTF-8 charset 在 common 无原生支持，留 JVM 半区（附加成员） */
    fun decodeStr(source: String?, charset: Charset): String? {
        source ?: return null
        return String(Base64LenientCore.decode(source), charset)
    }

    actual fun decode(input: ByteArray): ByteArray = Base64LenientCore.decode(input)
}
