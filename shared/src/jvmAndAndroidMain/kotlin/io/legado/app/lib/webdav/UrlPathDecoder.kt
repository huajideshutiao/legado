package io.legado.app.lib.webdav

import java.nio.charset.Charset

/**
 * URL 路径解码，行为对齐 hutool URLDecoder.decodeForPath：
 * %XX 转字节后按 charset 解码，'+' 不转空格，不合规的 '%' 原样输出。
 */
internal object UrlPathDecoder {

    private const val PERCENT = '%'.code.toByte()

    fun decode(str: String, charset: Charset): String {
        val length = str.length
        if (length == 0) return str
        val result = StringBuilder(length)
        var begin = 0
        for (i in 0 until length) {
            val c = str[i]
            // '%' 与 hex 字符积累成段做字节级解码，其余字符（含 '+'）原样直出
            if (c == '%' || isHexChar(c)) continue
            if (i > begin) result.decodeSub(str, begin, i, charset)
            result.append(c)
            begin = i + 1
        }
        if (begin < length) result.decodeSub(str, begin, length, charset)
        return result.toString()
    }

    /** [begin, end) 内只含 '%'/hex 字符，按 hutool 字节级算法解码后转 charset 字符串 */
    private fun StringBuilder.decodeSub(str: String, begin: Int, end: Int, charset: Charset) {
        val bytes = str.substring(begin, end).toByteArray(Charsets.ISO_8859_1)
        val buffer = ByteArray(bytes.size)
        var len = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == PERCENT && i + 1 < bytes.size) {
                val u = digit16(bytes[i + 1])
                if (u >= 0 && i + 2 < bytes.size) {
                    val l = digit16(bytes[i + 2])
                    if (l >= 0) {
                        buffer[len++] = ((u shl 4) + l).toByte()
                        i += 3
                        continue
                    }
                }
            }
            // 不合规的 % 或普通字节原样输出
            buffer[len++] = b
            i++
        }
        append(String(buffer, 0, len, charset))
    }

    private fun isHexChar(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun digit16(b: Byte): Int = Character.digit(b.toInt(), 16)
}
