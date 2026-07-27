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
 */
internal object MbedTlsDigest {

    fun digest(algorithm: String, data: ByteArray): ByteArray {
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
        val info = mdInfo(stripHmacPrefix(algorithm))
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

    /** 归一化算法名 (去连字符/大写) → md_info; 白名单外点名抛异常。 */
    internal fun mdInfo(algorithm: String): CPointer<mbedtls_md_info_t> {
        val name = when (val n = algorithm.uppercase().replace("-", "")) {
            "MD5", "SHA1", "SHA224", "SHA256", "SHA384", "SHA512", "RIPEMD160" -> n
            else -> throw IllegalArgumentException("MbedTlsDigest: unsupported algorithm '$algorithm'")
        }
        return mbedtls_md_info_from_string(name)
            ?: throw IllegalStateException("MbedTlsDigest: md_info_from_string($name) returned null")
    }

    /** 去掉 HMAC / Hmac / HMAC- 前缀, 剩余部分交给 [mdInfo] 归一。 */
    private fun stripHmacPrefix(algorithm: String): String {
        val upper = algorithm.uppercase()
        return when {
            upper.startsWith("HMAC-") -> upper.substring(5)
            upper.startsWith("HMAC") -> upper.substring(4)
            else -> upper
        }
    }
}
