package io.legado.app.utils

/**
 * MD5 门面：expect/actual。JVM 半区 actual 另有 md5Encode(InputStream) 流式附加重载。
 */
expect object MD5Utils {

    fun md5Encode(str: String?): String

    fun md5Encode16(str: String): String
}

/** 门面共用逻辑（common），各端 actual 委托此处。 */
internal object MD5UtilsCore {

    fun md5Encode(str: String?): String {
        if (str == null) return ""
        val bytes = str.encodeToByteArray()
        val digest = Md5Digest()
        digest.update(bytes, 0, bytes.size)
        return digest.digest().toHexLower()
    }

    fun md5Encode16(str: String): String {
        return md5Encode(str).substring(8, 24)
    }
}

internal fun ByteArray.toHexLower(): String {
    val hex = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(hex[v ushr 4]).append(hex[v and 0xf])
    }
    return out.toString()
}

/**
 * RFC 1321 纯 Kotlin MD5，支持流式 update（JVM 半区 InputStream 重载复用）。
 * 结果与 java.security.MessageDigest("MD5") 逐字节一致（jvmTest 向量对照）。
 */
internal class Md5Digest {

    private var h0 = 0x67452301
    private var h1 = -0x10325477 // 0xefcdab89
    private var h2 = -0x67452302 // 0x98badcfe
    private var h3 = 0x10325476

    private val buffer = ByteArray(64)
    private var bufferPos = 0
    private var byteCount = 0L
    private val m = IntArray(16)

    fun update(input: ByteArray, offset: Int, len: Int) {
        var off = offset
        var remaining = len
        byteCount += len
        if (bufferPos > 0) {
            val n = minOf(64 - bufferPos, remaining)
            input.copyInto(buffer, bufferPos, off, off + n)
            bufferPos += n
            off += n
            remaining -= n
            if (bufferPos == 64) {
                processBlock(buffer, 0)
                bufferPos = 0
            }
        }
        while (remaining >= 64) {
            processBlock(input, off)
            off += 64
            remaining -= 64
        }
        if (remaining > 0) {
            input.copyInto(buffer, 0, off, off + remaining)
            bufferPos = remaining
        }
    }

    fun digest(): ByteArray {
        // bit 长度须在补位前定格（update 会继续累加 byteCount）
        val bitLen = byteCount shl 3
        val padLen = ((55 - (byteCount % 64).toInt() + 64) % 64) + 1
        update(PADDING, 0, padLen)
        val lenBytes = ByteArray(8)
        for (i in 0..7) {
            lenBytes[i] = ((bitLen ushr (8 * i)) and 0xff).toByte()
        }
        update(lenBytes, 0, 8)
        val out = ByteArray(16)
        var j = 0
        for (h in intArrayOf(h0, h1, h2, h3)) {
            out[j++] = (h and 0xff).toByte()
            out[j++] = ((h ushr 8) and 0xff).toByte()
            out[j++] = ((h ushr 16) and 0xff).toByte()
            out[j++] = ((h ushr 24) and 0xff).toByte()
        }
        return out
    }

    private fun processBlock(block: ByteArray, offset: Int) {
        for (i in 0 until 16) {
            val o = offset + i * 4
            m[i] = (block[o].toInt() and 0xff) or
                    ((block[o + 1].toInt() and 0xff) shl 8) or
                    ((block[o + 2].toInt() and 0xff) shl 16) or
                    ((block[o + 3].toInt() and 0xff) shl 24)
        }
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        for (i in 0 until 64) {
            val f: Int
            val g: Int
            when {
                i < 16 -> {
                    f = (b and c) or (b.inv() and d)
                    g = i
                }

                i < 32 -> {
                    f = (d and b) or (d.inv() and c)
                    g = (5 * i + 1) and 15
                }

                i < 48 -> {
                    f = b xor c xor d
                    g = (3 * i + 5) and 15
                }

                else -> {
                    f = c xor (b or d.inv())
                    g = (7 * i) and 15
                }
            }
            val temp = d
            d = c
            c = b
            b += (a + f + K[i] + m[g]).rotateLeft(S[i])
            a = temp
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
    }

    companion object {
        private val PADDING = ByteArray(64).also { it[0] = 0x80.toByte() }

        private val S = intArrayOf(
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
        )

        // K[i] = floor(abs(sin(i+1)) * 2^32)，RFC 1321 附录常量表
        private val K = intArrayOf(
            0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
            0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
            0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
            0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
            0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
            0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
            0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
            0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
            0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
            0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
            0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
            0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
            0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
            0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
            0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
            0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt(),
        )
    }
}
