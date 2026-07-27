package io.legado.app.utils

import java.nio.charset.Charset

/**
 * [TextCharsetCodec] jvmAndAndroid actual: java.nio.charset.Charset 直通。
 * decode/encode 与原 TextFile 的 `String(bytes, offset, length, charset)` /
 * `str.toByteArray(charset)` 字节级一致。
 */
internal actual fun textCharsetCodec(name: String): TextCharsetCodec =
    JvmTextCharsetCodec(charset(name))

private class JvmTextCharsetCodec(private val charset: Charset) : TextCharsetCodec {

    override val name: String get() = charset.name()

    override fun decode(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, charset)

    override fun encode(str: String): ByteArray = str.toByteArray(charset)
}
