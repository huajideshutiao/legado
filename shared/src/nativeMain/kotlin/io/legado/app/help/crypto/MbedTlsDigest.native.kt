@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

import io.legado.app.nativecrypto.mbedtls.mbedtls_md
import io.legado.app.nativecrypto.mbedtls.mbedtls_md_get_size
import io.legado.app.nativecrypto.mbedtls.mbedtls_md_hmac
import io.legado.app.nativecrypto.mbedtls.mbedtls_md_info_from_string
import io.legado.app.nativecrypto.mbedtls.mbedtls_md_info_t
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

/**
 * mbedTLS md 层摘要/HMAC (iOS/鸿蒙主实现, 失败由 actual 回落 krypto/napi)。
 * 算法名归一化到 md_info_from_string 表名: MD5/SHA1/SHA224/SHA256/SHA384/SHA512/RIPEMD160。
 *
 * SHA3-224/256/384/512 与 HMAC-SHA3-*: 裁剪版 mbedTLS 未编译 sha3.c
 * (legado_mbedtls_config.h 无 MBEDTLS_SHA3_C, library/ 下也无 sha3.c), 走同文件的
 * 纯 Kotlin [Sha3Native] (FIPS 202 Keccak-f[1600], 已对拍 Python hashlib/hmac 全变体+边界长度)。
 * 归一化同时接受 `SHA3-256` / `SHA3_256` / `sha3-256` 变体, 与 jvm 端 MessageDigest 命名对齐。
 */
internal object MbedTlsDigest {

    fun digest(algorithm: String, data: ByteArray): ByteArray {
        sha3Bits(algorithm)?.let { return Sha3Native.digest(it, data) }
        val info = mdInfo(algorithm)
        val out = ByteArray(mbedtls_md_get_size(info).toInt())
        out.usePinned { o ->
            data.usePinnedInput { p, len ->
                mbedCheck("md($algorithm)", mbedtls_md(info, p, len, o.addressOf(0).reinterpret()))
            }
        }
        return out
    }

    fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val normalized = stripHmacPrefix(algorithm)
        sha3Bits(normalized)?.let { return Sha3Native.hmac(it, key, data) }
        val info = mdInfo(normalized)
        val out = ByteArray(mbedtls_md_get_size(info).toInt())
        out.usePinned { o ->
            key.usePinnedInput { kp, klen ->
                data.usePinnedInput { dp, dlen ->
                    mbedCheck(
                        "md_hmac($algorithm)",
                        mbedtls_md_hmac(info, kp, klen, dp, dlen, o.addressOf(0).reinterpret())
                    )
                }
            }
        }
        return out
    }

    /**
     * SHA3 变体判定: 归一化 (大写 + 去连字符/下划线/空格) 后匹配 SHA3-224/256/384/512,
     * 返回摘要位数; 非 SHA3 返回 null 走 mbedtls md 层。
     */
    private fun sha3Bits(algorithm: String): Int? {
        val name = algorithm.uppercase().replace("-", "").replace("_", "").replace(" ", "")
        return when (name) {
            "SHA3224" -> 224
            "SHA3256" -> 256
            "SHA3384" -> 384
            "SHA3512" -> 512
            else -> null
        }
    }

    /** 归一化算法名 (去连字符/大写) → md_info; 白名单外点名抛异常。 */
    internal fun mdInfo(algorithm: String): CPointer<mbedtls_md_info_t> {
        val name = when (val n = algorithm.uppercase().replace("-", "")) {
            "MD5", "SHA1", "SHA224", "SHA256", "SHA384", "SHA512", "RIPEMD160" -> n
            else -> throw IllegalArgumentException("MbedTlsDigest: unsupported algorithm '$algorithm'")
        }
        return mbedtls_md_info_from_string(name)
            ?: throw IllegalStateException("MbedTlsDigest: md_info_from_string($name) returned null")
    }

    /** 去掉 HMAC / Hmac / HMAC- 前缀, 剩余部分交给 [mdInfo] / [sha3Bits] 归一。 */
    private fun stripHmacPrefix(algorithm: String): String {
        val upper = algorithm.uppercase()
        return when {
            upper.startsWith("HMAC-") -> upper.substring(5)
            upper.startsWith("HMAC") -> upper.substring(4)
            else -> upper
        }
    }
}

/**
 * 纯 Kotlin SHA3 (FIPS 202) — Keccak-f[1600]。
 *
 * 背景: 项目内裁剪版 mbedTLS 3.6.7 (shared/src/cinterop/mbedtls) 只 vendor 了
 * md5/sha1/sha256/sha512/ripemd160 的 .c, 头文件 sha3.h 在但 sha3.c 未 vendor 且
 * legado_mbedtls_config.h 未开 MBEDTLS_SHA3_C; krypto (korlibs 4.0.10) 与 ohos napi 回落
 * 也都不支持 SHA3。故用纯 Kotlin 实现补齐 SHA3-224/256/384/512 + HMAC-SHA3-*,
 * 与 mbedtls md 层并列 (不依赖 cinterop, 无外部库)。
 *
 * 已验证: 算法逻辑与 Python hashlib (sha3_224/256/384/512) / hmac 对拍,
 * 覆盖空串、rate 边界 (rate-1/rate/rate+1) 与随机长度; 边界坑: 0x06 domain 字节落在
 * 块尾时与 pad10*1 收尾位合并为 0x86 (对照 Go x/crypto/sha3 的 |0x80)。
 */
internal object Sha3Native {

    /** Keccak-f[1600] 24 轮轮常数 (FIPS 202 Table 2)。 */
    // 注意: 含高位 (bit 63) 的常量在鸿蒙 K/N (CPF) 编译器下 hex 字面量报 Value out of range,
    // 故全部写成等价十进制负数形式 (值 = hex 按无符号 64 位解释后 - 2^64)。
    private val ROUND_CONSTANTS = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, -9223372036854742902L, -9223372034707259392L,
        0x000000000000808BL, 0x0000000080000001L, -9223372034707259263L, -9223372036854743031L,
        0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
        0x000000008000808BL, -9223372036854775669L, -9223372036854742903L, -9223372036854743037L,
        -9223372036854743038L, -9223372036854775680L, 0x000000000000800AL, -9223372034707292150L,
        -9223372034707259263L, -9223372036854742912L, 0x0000000080000001L, -9223372034707259384L
    )

    /** rho 旋转偏移 r[x][y] (FIPS 202 Table 1 / Keccak 参考实现)。 */
    private val ROTATION = arrayOf(
        intArrayOf(0, 36, 3, 41, 18),
        intArrayOf(1, 44, 10, 45, 2),
        intArrayOf(62, 6, 43, 15, 61),
        intArrayOf(28, 55, 25, 21, 56),
        intArrayOf(27, 20, 39, 8, 14)
    )

    /** SHA3 变体参数: (rate 字节, 输出字节)。 */
    private fun params(hashBits: Int): Pair<Int, Int> = when (hashBits) {
        224 -> 144 to 28
        256 -> 136 to 32
        384 -> 104 to 48
        512 -> 72 to 64
        else -> throw IllegalArgumentException("Sha3Native: SHA3-224/256/384/512 only, got SHA3-$hashBits")
    }

    /**
     * SHA3 摘要。
     *
     * absorb: 按 rate 分块异或进状态; 末块按 pad10*1 规则补 domain 字节 0x06 与收尾 0x80
     * (0x06 落块尾时合并为 0x86, 不再补块); squeeze: 每 rate 字节取一次输出, 不足续 permute。
     */
    fun digest(hashBits: Int, data: ByteArray): ByteArray {
        val (rate, outLen) = params(hashBits)
        val state = LongArray(25)
        var offset = 0
        while (offset + rate <= data.size) {
            permuteAbsorb(state, data, offset, rate)
            offset += rate
        }
        // 末块: data 余量 + 0x06 domain + 0x80 收尾 (0x06 恰在块尾时两者合并)
        val final = ByteArray(rate)
        val take = data.size - offset
        if (take > 0) data.copyInto(final, 0, offset, offset + take)
        final[take] = 0x06.toByte()
        if (take < rate - 1) {
            final[rate - 1] = 0x80.toByte()
        } else {
            final[rate - 1] = 0x86.toByte()
        }
        permuteAbsorb(state, final, 0, rate)
        // squeeze
        val out = ByteArray(outLen)
        var o = 0
        while (o < outLen) {
            val n = minOf(rate, outLen - o)
            for (i in 0 until n) {
                out[o + i] = ((state[i / 8] ushr ((i % 8) * 8)) and 0xFF).toByte()
            }
            if (o + n < outLen) keccakF(state)
            o += n
        }
        return out
    }

    /** HMAC-SHA3: 标准 RFC 2104 结构 (块 = rate 字节, 长 key 先摘要)。 */
    fun hmac(hashBits: Int, key: ByteArray, data: ByteArray): ByteArray {
        val rate = params(hashBits).first
        val k = if (key.size > rate) digest(hashBits, key) else key
        val keyBlock = ByteArray(rate)
        k.copyInto(keyBlock)
        val ipad = ByteArray(rate) { (keyBlock[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(rate) { (keyBlock[it].toInt() xor 0x5C).toByte() }
        return digest(hashBits, opad + digest(hashBits, ipad + data))
    }

    /** 把一整块数据异或进状态后做一次 Keccak-f 置换。 */
    private fun permuteAbsorb(state: LongArray, block: ByteArray, offset: Int, length: Int) {
        for (i in 0 until length) {
            val b = block[offset + i].toLong() and 0xFF
            state[i / 8] = state[i / 8] xor (b shl ((i % 8) * 8))
        }
        keccakF(state)
    }

    /** Keccak-f[1600] 置换 (theta → rho+pi → chi → iota, 24 轮)。 */
    private fun keccakF(state: LongArray) {
        val c = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            // theta
            for (x in 0 until 5) {
                c[x] =
                    state[x] xor state[x + 5] xor state[x + 10] xor state[x + 15] xor state[x + 20]
            }
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor c[(x + 1) % 5].rotateLeft(1)
                for (y in 0 until 5) {
                    state[x + 5 * y] = state[x + 5 * y] xor d
                }
            }
            // rho + pi: B[y, 2x+3y] = rotl(A[x,y], r[x][y])
            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    val rotated = state[x + 5 * y].rotateLeft(ROTATION[x][y])
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = rotated
                }
            }
            // chi: A[x,y] = B[x,y] ^ (~B[x+1,y] & B[x+2,y])
            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    state[x + 5 * y] =
                        b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
                }
            }
            // iota
            state[0] = state[0] xor ROUND_CONSTANTS[round]
        }
    }
}
