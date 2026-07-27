@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

import korlibs.crypto.AES
import korlibs.crypto.CipherMode
import korlibs.crypto.CipherPadding
import korlibs.crypto.CipherWithModeAndPadding
import korlibs.crypto.HMAC
import korlibs.crypto.MD5
import korlibs.crypto.SHA1
import korlibs.crypto.SHA256
import korlibs.crypto.SHA512
import korlibs.crypto.SecureRandom
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA224
import platform.CoreCrypto.CC_SHA384

/**
 * iOS actual: 摘要 / HMAC / AES。主实现 mbedTLS ([MbedTlsDigest]/[MbedTlsCipher]/[MbedTlsRng]),
 * 任意异常回落既有 krypto (korlibs.crypto) + CommonCrypto (SHA-224/384) 路径。
 *
 * 注意: mbedTLS 目标码需 mac 侧编 libmbedtls.a 链入 (见 shared/src/cinterop/mbedtls/README.md);
 * 缺 .a 是链接期失败而非运行期, runtime 回落救不了缺符号。
 *
 * 行为字节级对齐 jvmAndAndroidMain (javax.crypto / hutool)。
 */
actual object NativeDigestOps {

    actual fun digest(algorithm: String, data: ByteArray): ByteArray = mbedTlsOrFallback(
        { MbedTlsDigest.digest(algorithm, data) },
        { kryptoDigest(algorithm, data) }
    )
}

actual object NativeHmacOps {

    actual fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray = mbedTlsOrFallback(
        { MbedTlsDigest.hmac(algorithm, key, data) },
        { kryptoHmac(algorithm, key, data) }
    )
}

actual object NativeAesOps {

    actual fun encryptEcbPkcs7(key: ByteArray, data: ByteArray): ByteArray =
        encrypt(key, data, "ECB", "PKCS7PADDING", null)

    actual fun decryptEcbPkcs7(key: ByteArray, data: ByteArray): ByteArray =
        decrypt(key, data, "ECB", "PKCS7PADDING", null)

    actual fun encrypt(
        key: ByteArray,
        data: ByteArray,
        mode: String,
        padding: String,
        iv: ByteArray?
    ): ByteArray = mbedTlsOrFallback(
        { MbedTlsCipher.encrypt("AES", mode, padding, key, iv, data) },
        { aes(key, toMode(mode), toPadding(padding), iv).encrypt(data) }
    )

    actual fun decrypt(
        key: ByteArray,
        data: ByteArray,
        mode: String,
        padding: String,
        iv: ByteArray?
    ): ByteArray = mbedTlsOrFallback(
        { MbedTlsCipher.decrypt("AES", mode, padding, key, iv, data) },
        { aes(key, toMode(mode), toPadding(padding), iv).decrypt(data) }
    )

    /** 主走 mbedTLS CTR_DRBG; 回落 krypto SecureRandom (SecRandomCopyBytes)。 */
    actual fun randomKey(size: Int): ByteArray = mbedTlsOrFallback(
        { MbedTlsRng.random(size) },
        { SecureRandom.nextBytes(size) }
    )

    private fun aes(
        key: ByteArray,
        mode: CipherMode,
        padding: CipherPadding,
        iv: ByteArray?
    ) = CipherWithModeAndPadding(AES(key), mode, padding, iv)

    private fun toMode(mode: String): CipherMode = when (mode) {
        "ECB" -> CipherMode.ECB
        "CBC" -> CipherMode.CBC
        "PCBC" -> CipherMode.PCBC
        "CFB" -> CipherMode.CFB
        "OFB" -> CipherMode.OFB
        "CTR" -> CipherMode.CTR
        else -> throw UnsupportedOperationException("NativeAesOps: unsupported AES mode '$mode' on iOS")
    }

    private fun toPadding(padding: String): CipherPadding = when (padding) {
        "NOPADDING" -> CipherPadding.NoPadding
        "PKCS7PADDING" -> CipherPadding.PKCS7Padding
        "ANSIX923PADDING" -> CipherPadding.ANSIX923Padding
        "ISO10126PADDING" -> CipherPadding.ISO10126Padding
        "ZEROPADDING" -> CipherPadding.ZeroPadding
        else -> throw UnsupportedOperationException("NativeAesOps: unsupported AES padding '$padding' on iOS")
    }
}

private const val CC_SHA224_LEN = 28
private const val CC_SHA384_LEN = 48

/** krypto/CommonCrypto 摘要回落路径 (mbedTLS 异常时)。 */
private fun kryptoDigest(algorithm: String, data: ByteArray): ByteArray {
    return when (normalizeDigestName(algorithm)) {
        "MD5" -> MD5.digest(data).bytes
        "SHA1" -> SHA1.digest(data).bytes
        "SHA224" -> ccSha224(data)
        "SHA256" -> SHA256.digest(data).bytes
        "SHA384" -> ccSha384(data)
        "SHA512" -> SHA512.digest(data).bytes
        else -> throw IllegalArgumentException("Unsupported digest algorithm: $algorithm")
    }
}

/** krypto/CommonCrypto HMAC 回落路径 (mbedTLS 异常时)。 */
private fun kryptoHmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    // 去掉 HMAC/Hmac 前缀 (含连字符变体) 后归一为标准摘要名
    val upper = algorithm.uppercase()
    val stripped = when {
        upper.startsWith("HMAC-") -> upper.substring(5)
        upper.startsWith("HMAC") -> upper.substring(4)
        else -> upper
    }
    return when (normalizeDigestName(stripped)) {
        "MD5" -> HMAC.hmacMD5(key, data).bytes
        "SHA1" -> HMAC.hmacSHA1(key, data).bytes
        "SHA224" -> hmacRfc2104(64, key, data, ::ccSha224)
        "SHA256" -> HMAC.hmacSHA256(key, data).bytes
        "SHA384" -> hmacRfc2104(128, key, data, ::ccSha384)
        "SHA512" -> HMAC.hmacSHA512(key, data).bytes
        else -> throw IllegalArgumentException("Unsupported HMac algorithm: $algorithm")
    }
}

/** 摘要名归一: 去连字符 + 大写 (MD5 / SHA1 / SHA224 / SHA256 / SHA384 / SHA512)。 */
private fun normalizeDigestName(algorithm: String): String =
    algorithm.uppercase().replace("-", "")

/** CommonCrypto one-shot SHA-224 (usePinned 不能对空数组取址, 空输入用 1 字节占位 + len=0)。 */
private fun ccSha224(data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA224_LEN)
    val input = if (data.isEmpty()) ByteArray(1) else data
    input.usePinned { inPin ->
        out.usePinned { outPin ->
            CC_SHA224(inPin.addressOf(0), data.size.convert(), outPin.addressOf(0).reinterpret<UByteVar>())
        }
    }
    return out
}

/** CommonCrypto one-shot SHA-384。 */
private fun ccSha384(data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA384_LEN)
    val input = if (data.isEmpty()) ByteArray(1) else data
    input.usePinned { inPin ->
        out.usePinned { outPin ->
            CC_SHA384(inPin.addressOf(0), data.size.convert(), outPin.addressOf(0).reinterpret<UByteVar>())
        }
    }
    return out
}

/** RFC 2104 HMAC, 供 krypto 未提供的 SHA-224/SHA-384 复用 CommonCrypto 摘要。 */
private fun hmacRfc2104(
    blockSize: Int,
    key: ByteArray,
    data: ByteArray,
    digest: (ByteArray) -> ByteArray
): ByteArray {
    var k = if (key.size > blockSize) digest(key) else key
    if (k.size < blockSize) k = k.copyOf(blockSize)
    val iPad = ByteArray(blockSize) { (k[it].toInt() xor 0x36).toByte() }
    val oPad = ByteArray(blockSize) { (k[it].toInt() xor 0x5C).toByte() }
    return digest(oPad + digest(iPad + data))
}
