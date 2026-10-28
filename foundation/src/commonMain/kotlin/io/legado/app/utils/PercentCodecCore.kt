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

    /** 字符是否在安全表内 (原样输出, 不参与百分号编码)。 */
    fun isSafe(c: Char): Boolean = c.code < SIZE && safe[c.code]

    /**
     * 逐码点编码, 但连续的非安全字符簇合并为一段、一段只调用一次 [toBytes]。
     *
     * 字节结果取决于 [toBytes] 的编码器生命周期 (外部约束):
     * - 无状态字符集 (UTF-8/Latin 系/GBK 系): 逐簇与逐段字节一致;
     * - 每次调用都是新转换 (如 `String.toByteArray(charset)`): 与 java.net.URLEncoder
     *   「连续不安全字符一次编码」同语义, 带 BOM 的 UTF-16 每段各一次 BOM;
     * - 跨段共享编码器 ([PercentCodec.encode] 的 TextCharsetCodec 重载): BOM 全调用仅一次,
     *   对齐 hutool PercentCodec.encode 的循环外 writer 语义。
     *
     * 合并把每次字节化的输入从一个码点提升为一段, 减少跨线程往返次数。
     */
    fun encodeMergingRuns(str: CharSequence, toBytes: (String) -> ByteArray): String {
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
            val start = i
            while (i < str.length) {
                val run = str[i]
                if (run.code < SIZE && safe[run.code]) break
                i += if (run.isHighSurrogate() && i + 1 < str.length && str[i + 1].isLowSurrogate()) 2 else 1
            }
            appendPercentEncoded(out, str, start, i, toBytes)
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
                    // 整段 `%XX` 原样搬走后必须跳过三位: 只前进 1 位会让后两位十六进制字符
                    // 在下一轮被当成普通字符再追加一次 (`%20` → `%2020`, `%E7` → `%E7E7`),
                    // 编码过的 query/路径会被写成乱码字节 (搜索关键词、带转义的图片地址全中招)
                    out.append(str, i, i + 3)
                    i += 3
                } else {
                    // 不成套的 `%`: 只有这一位是百分号本身, 按 `%25` 写掉后前进 1 位
                    out.append("%25")
                    i++
                }
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
