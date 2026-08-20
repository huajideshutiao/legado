package io.legado.app.utils

/**
 * 宽松 Base64 解码，行为对齐 hutool Base64Decoder：
 * 标准与 URL-safe 字母表混收，非法字符（含中途 '='）忽略，末尾不足 8bit 丢弃。
 * expect 门面：JVM 半区 actual 另有 decodeStr(source, charset) 附加重载（common 无 Charset）。
 */
expect object Base64Lenient {

    fun decode(source: String): ByteArray

    fun decodeStr(source: String?): String?

    fun decode(input: ByteArray): ByteArray
}

/** 解码算法主体（common），各端 actual 委托此处。 */
internal object Base64LenientCore {

    private const val PADDING = (-2).toByte()

    /**
     * hutool 同款解码表：-1 非法，-2 padding；'+'/'-'→62，'/'/'_'→63。
     * 末行补齐到 0x7f（hutool 原表止于 0x7a，`{|}~`/DEL 会下标越界，与本类"非法字符忽略"的契约矛盾）。
     */
    private val DECODE_TABLE = byteArrayOf(
        // 0   1   2   3   4   5   6   7   8   9   A   B   C   D   E   F
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, // 00-0f
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, // 10-1f
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, 62, -1, 63, // 20-2f + - /
        52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -2, -1, -1, // 30-3f 0-9 =
        -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, // 40-4f A-O
        15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, 63, // 50-5f P-Z _
        -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, // 60-6f a-o
        41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1 // 70-7f p-z
    )

    fun decode(source: String): ByteArray = decode(source.encodeToByteArray())

    fun decodeStr(source: String?): String? {
        source ?: return null
        return decode(source).decodeToString()
    }

    fun decode(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input
        val maxPos = input.size - 1
        val pos = intArrayOf(0)
        var octetId = 0
        val octet = ByteArray(input.size * 3 / 4)
        while (pos[0] <= maxPos) {
            val s0 = nextValid(input, pos, maxPos)
            val s1 = nextValid(input, pos, maxPos)
            val s2 = nextValid(input, pos, maxPos)
            val s3 = nextValid(input, pos, maxPos)
            if (s1 != PADDING) octet[octetId++] = ((s0.toInt() shl 2) or (s1.toInt() ushr 4)).toByte()
            if (s2 != PADDING) octet[octetId++] =
                (((s1.toInt() and 0xf) shl 4) or (s2.toInt() ushr 2)).toByte()
            if (s3 != PADDING) octet[octetId++] = (((s2.toInt() and 3) shl 6) or s3.toInt()).toByte()
        }
        return if (octetId == octet.size) octet else octet.copyOf(octetId)
    }

    private fun nextValid(input: ByteArray, pos: IntArray, maxPos: Int): Byte {
        while (pos[0] <= maxPos) {
            val b = input[pos[0]]
            pos[0]++
            if (b > -1) {
                val decoded = DECODE_TABLE[b.toInt()]
                if (decoded > -1) return decoded
            }
        }
        return PADDING
    }
}
