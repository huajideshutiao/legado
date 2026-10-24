package io.legado.app.help.crypto

/**
 * 纯 Kotlin 摘要侧车 (iOS/鸿蒙 共用): 系统 API 缺位算法的统一补齐点。
 *
 * - SM3 (GB/T 32905-2016): iOS CommonCrypto 无, 鸿蒙虽有但走纯 Kotlin 保持双端同源;
 * - SHA3-224/256/384/512: 见 [Sha3Native];
 * - 上述算法的 HMAC (RFC 2104, 64 字节块)。
 *
 * [digest]/[hmac] 命中白名单返回结果, 未命中返回 null 由平台原语接管。
 *
 * 对拍基准: SM3("abc") = 66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0 (GB/T 32905-2016 例 A.1)。
 */
internal object KotlinDigestNative {

    /** 归一化: 大写 + 去连字符/下划线/空格 (iOS/ohos actual 复用, 唯一实现)。 */
    internal fun normalize(algorithm: String): String =
        algorithm.uppercase().replace("-", "").replace("_", "").replace(" ", "")

    /** 去掉 HMAC 前缀 (HMAC-/HMAC) (iOS actual 复用, 唯一实现)。 */
    internal fun stripHmac(algorithm: String): String {
        val u = algorithm.uppercase()
        return when {
            u.startsWith("HMAC-") -> u.substring(5)
            u.startsWith("HMAC") -> u.substring(4)
            else -> u
        }
    }

    /** 纯 Kotlin 摘要; 未命中返回 null。 */
    fun digest(algorithm: String, data: ByteArray): ByteArray? = when (normalize(algorithm)) {
        "SM3" -> Sm3.digest(data)
        "SHA3224" -> Sha3Native.digest(224, data)
        "SHA3256" -> Sha3Native.digest(256, data)
        "SHA3384" -> Sha3Native.digest(384, data)
        "SHA3512" -> Sha3Native.digest(512, data)
        else -> null
    }

    /** 纯 Kotlin HMAC; 未命中返回 null。 */
    fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray? {
        val name = normalize(stripHmac(algorithm))
        return when (name) {
            "SM3" -> GenericHmac.hmac(64, { Sm3.digest(it) }, key, data)
            "SHA3224" -> Sha3Native.hmac(224, key, data)
            "SHA3256" -> Sha3Native.hmac(256, key, data)
            "SHA3384" -> Sha3Native.hmac(384, key, data)
            "SHA3512" -> Sha3Native.hmac(512, key, data)
            else -> null
        }
    }
}

/** RFC 2104 HMAC (块大小任意, 覆盖 64 字节块系摘要; SHA3 系走 Sha3Native 自带实现)。 */
internal object GenericHmac {

    fun hmac(blockSize: Int, digest: (ByteArray) -> ByteArray, key: ByteArray, data: ByteArray): ByteArray {
        val k = if (key.size > blockSize) digest(key) else key
        val keyBlock = ByteArray(blockSize)
        k.copyInto(keyBlock)
        val ipad = ByteArray(blockSize) { (keyBlock[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(blockSize) { (keyBlock[it].toInt() xor 0x5C).toByte() }
        return digest(opad + digest(ipad + data))
    }
}

/** SM3 摘要 (GB/T 32905-2016)。 */
internal object Sm3 {

    private val IV = intArrayOf(
        0x7380166F, 0x4914B2B9, 0x172442D7, 0xDA8A0600.toInt(),
        0xA96F30BC.toInt(), 0x163138AA, 0xE38DEE4D.toInt(), 0xB0FB0E4E.toInt()
    )

    fun digest(data: ByteArray): ByteArray {
        // padding: 0x80 + 零 + 64 位大端比特长 (与 SHA 系一致)
        val bitLen = data.size.toLong() * 8
        val paddedLen = ((data.size + 8) / 64 + 1) * 64
        val buf = ByteArray(paddedLen)
        data.copyInto(buf)
        buf[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            buf[paddedLen - 1 - i] = (bitLen ushr (i * 8)).toByte()
        }
        var v = IV.copyOf()
        var off = 0
        while (off < paddedLen) {
            v = compress(v, buf, off)
            off += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (v[i] ushr 24).toByte()
            out[i * 4 + 1] = (v[i] ushr 16).toByte()
            out[i * 4 + 2] = (v[i] ushr 8).toByte()
            out[i * 4 + 3] = v[i].toByte()
        }
        return out
    }

    private fun compress(v: IntArray, block: ByteArray, off: Int): IntArray {
        val w = IntArray(68)
        for (i in 0 until 16) {
            w[i] = ((block[off + i * 4].toInt() and 0xFF) shl 24) or
                ((block[off + i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((block[off + i * 4 + 2].toInt() and 0xFF) shl 8) or
                (block[off + i * 4 + 3].toInt() and 0xFF)
        }
        for (j in 16 until 68) {
            w[j] = p1(w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15)) xor rotl(w[j - 13], 7) xor w[j - 6]
        }
        val wp = IntArray(64)
        for (j in 0 until 64) {
            wp[j] = w[j] xor w[j + 4]
        }
        var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]
        var e = v[4]; var f = v[5]; var g = v[6]; var h = v[7]
        for (j in 0 until 64) {
            val t = if (j < 16) 0x79CC4519 else 0x7A879D8A
            val ss1 = rotl(rotl(a, 12) + e + rotl(t, j % 32), 7)
            val ss2 = ss1 xor rotl(a, 12)
            val tt1 = ff(j, a, b, c) + d + ss2 + wp[j]
            val tt2 = gg(j, e, f, g) + h + ss1 + w[j]
            d = c
            c = rotl(b, 9)
            b = a
            a = tt1
            h = g
            g = rotl(f, 19)
            f = e
            e = p0(tt2)
        }
        return intArrayOf(a xor v[0], b xor v[1], c xor v[2], d xor v[3], e xor v[4], f xor v[5], g xor v[6], h xor v[7])
    }

    private fun rotl(x: Int, n: Int): Int = x.rotateLeft(n)
    private fun p0(x: Int): Int = x xor rotl(x, 9) xor rotl(x, 17)
    private fun p1(x: Int): Int = x xor rotl(x, 15) xor rotl(x, 23)
    private fun ff(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x and z) or (y and z)
    private fun gg(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x.inv() and z)
}
