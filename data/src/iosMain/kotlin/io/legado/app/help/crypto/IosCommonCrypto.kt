@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCryptorCreateWithMode
import platform.CoreCrypto.CCCryptorFinal
import platform.CoreCrypto.CCCryptorRelease
import platform.CoreCrypto.CCCryptorUpdate
import platform.CoreCrypto.CCCryptorRefVar
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA224
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA384
import platform.CoreCrypto.CC_SHA512
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.ccNoPadding
import platform.CoreCrypto.kCCAlgorithm3DES
import platform.CoreCrypto.kCCAlgorithmAES128
import platform.CoreCrypto.kCCAlgorithmDES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgMD5
import platform.CoreCrypto.kCCHmacAlgSHA1
import platform.CoreCrypto.kCCHmacAlgSHA224
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCHmacAlgSHA384
import platform.CoreCrypto.kCCHmacAlgSHA512
import platform.CoreCrypto.kCCModeCBC
import platform.CoreCrypto.kCCModeCFB
import platform.CoreCrypto.kCCModeCTR
import platform.CoreCrypto.kCCModeECB
import platform.CoreCrypto.kCCModeOFB
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS CommonCrypto (platform.CoreCrypto 绑定) / Security.framework 原语胶水。
 *
 * 只用公开 API: CCCryptorCreateWithMode (kCCModeECB/CBC/CFB/CTR/OFB × AES/DES/3DES,
 * 全部 ccNoPadding), CC_MD5/CC_SHA* 一次性摘要, CCHmac, SecRandomCopyBytes。
 * - 模式语义: kCCModeCFB 缺省即 CFB128, 对齐 JCA "AES/CFB/..." 与原 mbedTLS CFB128;
 * - padding 由 [SymPadding] 在 Kotlin 层处理 (平台恒 NoPadding);
 * - GCM 不在公开 API (CCCryptorGCM* 系 SPI, App Store 拒审面), 由纯 Kotlin [GcmNative] 接管;
 * - C 枚举常量 (匿名 enum) 经 typedef uint32_t 暴露, K/N 绑定为 Int, 传参处统一 .convert()。
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosCommonCrypto {

    /** NoPadding 裸加解密 (输入已由 [SymPadding] 补齐, ECB/CBC 必须块对齐)。 */
    fun crypt(
        encrypt: Boolean,
        algorithm: String,
        mode: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val op = if (encrypt) kCCEncrypt else kCCDecrypt
        val alg = algorithmOf(algorithm)
        val ccMode = modeOf(mode)
        val realIv = if (mode == "ECB") null else iv
        memScoped {
            val cryptorPtr = alloc<CCCryptorRefVar>()
            val status = if (realIv == null) {
                key.usePinned { kp ->
                    CCCryptorCreateWithMode(
                        op.convert(), ccMode.convert(), alg.convert(), ccNoPadding.convert(),
                        null, kp.addressOf(0), key.size.convert(),
                        null, 0.convert(), 0, 0u, cryptorPtr.ptr
                    )
                }
            } else {
                realIv.usePinned { vp ->
                    key.usePinned { kp ->
                        CCCryptorCreateWithMode(
                            op.convert(), ccMode.convert(), alg.convert(), ccNoPadding.convert(),
                            vp.addressOf(0), kp.addressOf(0), key.size.convert(),
                            null, 0.convert(), 0, 0u, cryptorPtr.ptr
                        )
                    }
                }
            }
            checkStatus("CCCryptorCreateWithMode($algorithm/$mode)", status)
            val cryptor = cryptorPtr.value
                ?: error("IosCommonCrypto: CCCryptorCreateWithMode returned null cryptor")
            try {
                val out = ByteArray(data.size + BLOCK_PAD)
                val moved = alloc<ULongVar>()
                var total = 0
                out.usePinned { opPin ->
                    data.usePinned { ipPin ->
                        val s1 = CCCryptorUpdate(
                            cryptor, ipPin.addressOf(0), data.size.convert(),
                            opPin.addressOf(0), out.size.convert(), moved.ptr
                        )
                        checkStatus("CCCryptorUpdate($algorithm/$mode)", s1)
                        total = moved.value.toInt()
                        val s2 = CCCryptorFinal(
                            cryptor, opPin.addressOf(total), (out.size - total).convert(), moved.ptr
                        )
                        checkStatus("CCCryptorFinal($algorithm/$mode)", s2)
                        total += moved.value.toInt()
                    }
                }
                return out.copyOf(total)
            } finally {
                CCCryptorRelease(cryptor)
            }
        }
    }

    /** 摘要: MD5/SHA1/SHA224/SHA256/SHA384/SHA512 (CommonDigest.h 一次性函数)。 */
    fun digest(algorithm: String, data: ByteArray): ByteArray {
        val out = when (algorithm) {
            "MD5" -> ByteArray(16)
            "SHA1" -> ByteArray(20)
            "SHA224" -> ByteArray(28)
            "SHA256" -> ByteArray(32)
            "SHA384" -> ByteArray(48)
            "SHA512" -> ByteArray(64)
            else -> throw UnsupportedOperationException(
                "IosCommonCrypto: unsupported digest '$algorithm' (SM3/SHA3 走纯 Kotlin 侧车)"
            )
        }
        memScoped {
            out.usePinned { mp ->
                data.usePinned { dp ->
                    val ret = when (algorithm) {
                        "MD5" -> CC_MD5(dp.addressOf(0), data.size.convert(), mp.addressOf(0).reinterpret<UByteVar>())
                        "SHA1" -> CC_SHA1(dp.addressOf(0), data.size.convert(), mp.addressOf(0).reinterpret<UByteVar>())
                        "SHA224" -> CC_SHA224(dp.addressOf(0), data.size.convert(), mp.addressOf(0).reinterpret<UByteVar>())
                        "SHA256" -> CC_SHA256(dp.addressOf(0), data.size.convert(), mp.addressOf(0).reinterpret<UByteVar>())
                        "SHA384" -> CC_SHA384(dp.addressOf(0), data.size.convert(), mp.addressOf(0).reinterpret<UByteVar>())
                        else -> CC_SHA512(dp.addressOf(0), data.size.convert(), mp.addressOf(0).reinterpret<UByteVar>())
                    }
                    check(ret != null) { "IosCommonCrypto: $algorithm digest failed" }
                }
            }
        }
        return out
    }

    /** HMAC (CommonHMAC.h CCHmac, 白名单与摘要一致, 长度按摘要字节)。 */
    fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(
            when (algorithm) {
                "MD5" -> 16
                "SHA1" -> 20
                "SHA224" -> 28
                "SHA256" -> 32
                "SHA384" -> 48
                "SHA512" -> 64
                else -> throw UnsupportedOperationException("IosCommonCrypto: unsupported HMAC '$algorithm'")
            }
        )
        memScoped {
            out.usePinned { mp ->
                key.usePinned { kp ->
                    data.usePinned { dp ->
                        CCHmac(
                            hmacAlg(algorithm).convert(),
                            kp.addressOf(0), key.size.convert(),
                            dp.addressOf(0), data.size.convert(),
                            mp.addressOf(0)
                        )
                    }
                }
            }
        }
        return out
    }

    /** SecRandomCopyBytes (Security.framework, kSecRandomDefault = 系统熵源)。 */
    fun randomBytes(size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { p ->
            val ret = SecRandomCopyBytes(kSecRandomDefault, size.convert(), p.addressOf(0))
            checkStatus("SecRandomCopyBytes", ret)
        }
        return out
    }

    private fun modeOf(mode: String): UInt = when (mode) {
        "ECB" -> kCCModeECB
        "CBC" -> kCCModeCBC
        "CFB" -> kCCModeCFB
        "OFB" -> kCCModeOFB
        "CTR" -> kCCModeCTR
        else -> throw UnsupportedOperationException(
            "IosCommonCrypto: unsupported mode '$mode' (PCBC 无原生实现)"
        )
    }

    private fun algorithmOf(algorithm: String): UInt = when (algorithm) {
        "AES" -> kCCAlgorithmAES128
        "DES" -> kCCAlgorithmDES
        "DESEDE" -> kCCAlgorithm3DES
        else -> throw UnsupportedOperationException("IosCommonCrypto: unsupported algorithm '$algorithm'")
    }

    private fun hmacAlg(algorithm: String): UInt = when (algorithm) {
        "MD5" -> kCCHmacAlgMD5
        "SHA1" -> kCCHmacAlgSHA1
        "SHA224" -> kCCHmacAlgSHA224
        "SHA256" -> kCCHmacAlgSHA256
        "SHA384" -> kCCHmacAlgSHA384
        "SHA512" -> kCCHmacAlgSHA512
        else -> throw UnsupportedOperationException("IosCommonCrypto: unsupported HMAC '$algorithm'")
    }

    private fun checkStatus(op: String, status: Int) {
        require(status == kCCSuccess) { "IosCommonCrypto: $op failed (status=$status)" }
    }

    /** Update/Final 各可能多写一块 padding, 输出缓冲留足余量。 */
    private const val BLOCK_PAD = 32
}
