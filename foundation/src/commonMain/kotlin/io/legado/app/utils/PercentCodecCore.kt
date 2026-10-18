package io.legado.app.utils

/**
 * 百分号编码算法主体（common）：安全表构建与逐码点编码循环；
 * 字节化经 toBytes 注入（Charset 为 JVM 概念，公开门面留 jvmAndAndroid 半区）。
 */
internal class PercentCodecCore(private val safe: BooleanArray) {

    fun orNew(other: PercentCodecCore): PercentCodecCore {
        val merged = safe.copyOf()
        for (i in merged.indices) {
            if (other.safe[i]) merged[i] = true
        }
        return PercentCodecCore(merged)
    }

    fun encode(str: CharSequence, toBytes: (String) -> ByteArray): String {
        if (str.isEmpty()) return str.toString()
        val out = StringBuilder(str.length)
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c.code < SIZE && safe[c.code]) {
                out.append(c)
                i++
                continue
            }
            val end = surrogateClusterEnd(str, i)
            appendPercentEncoded(out, str, i, end, toBytes)
            i = end
        }
        return out.toString()
    }

    /**
     * 逐码点编码, 但成套的 `%XX` 原样保留 (幂等, 不二次转义), 不成套的 `%` 转为 `%25`。
     * RFC 3986 §2.1: 三元组 `%` HEXDIG HEXDIG 是唯一的转义形态, 其余 `%` 只能写成 `%25`。
     */
    fun encodeKeepEscapes(str: CharSequence, toBytes: (String) -> ByteArray): String {
        if (str.isEmpty()) return str.toString()
        val out = StringBuilder(str.length)
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '%') {
                if (i + 2 < str.length && isHexDigit(str[i + 1]) && isHexDigit(str[i + 2])) {
                    out.append(str, i, i + 3)
                } else {
                    out.append("%25")
                }
                i++
                continue
            }
            if (c.code < SIZE && safe[c.code]) {
                out.append(c)
                i++
                continue
            }
            val end = surrogateClusterEnd(str, i)
            appendPercentEncoded(out, str, i, end, toBytes)
            i = end
        }
        return out.toString()
    }

    private fun surrogateClusterEnd(str: CharSequence, i: Int): Int {
        val c = str[i]
        return if (c.isHighSurrogate() && i + 1 < str.length && str[i + 1].isLowSurrogate()) i + 2 else i + 1
    }

    private fun appendPercentEncoded(
        out: StringBuilder,
        str: CharSequence,
        from: Int,
        to: Int,
        toBytes: (String) -> ByteArray,
    ) {
        for (b in toBytes(str.substring(from, to))) {
            val v = b.toInt() and 0xff
            out.append('%').append(HEX[v ushr 4]).append(HEX[v and 0xf])
        }
    }

    companion object {
        private const val SIZE = 128
        private val HEX = "0123456789ABCDEF".toCharArray()

        private fun isHexDigit(c: Char): Boolean =
            c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        fun of(chars: CharSequence): PercentCodecCore {
            val safe = BooleanArray(SIZE)
            for (c in chars) safe[c.code] = true
            return PercentCodecCore(safe)
        }
    }
}
