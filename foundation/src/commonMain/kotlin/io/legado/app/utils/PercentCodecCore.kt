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
            val end = if (c.isHighSurrogate() && i + 1 < str.length && str[i + 1].isLowSurrogate()) {
                i + 2
            } else {
                i + 1
            }
            for (b in toBytes(str.substring(i, end))) {
                val v = b.toInt() and 0xff
                out.append('%').append(HEX[v ushr 4]).append(HEX[v and 0xf])
            }
            i = end
        }
        return out.toString()
    }

    companion object {
        private const val SIZE = 128
        private val HEX = "0123456789ABCDEF".toCharArray()

        fun of(chars: CharSequence): PercentCodecCore {
            val safe = BooleanArray(SIZE)
            for (c in chars) safe[c.code] = true
            return PercentCodecCore(safe)
        }
    }
}
