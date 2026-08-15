@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

/**
 * iOS actual: 摘要 / HMAC / AES。纯 mbedTLS 实现 ([MbedTlsOps]), 无第三方回落:
 * 2026-08 已移除 korlibs krypto (仓库归档) — mbedTLS md 层已覆盖 MD5/SHA1/SHA224/
 * SHA256/SHA384/SHA512/RIPEMD160, SHA3 由纯 Kotlin [Sha3Native] 补齐; AES 各模式
 * (ECB/CBC/CFB/OFB/CTR/GCM) 由 MbedTlsCipher 提供。
 *
 * 行为回退: PCBC 模式 mbedTLS 无原生实现 (MbedTlsCipher 点名抛异常), 移除 krypto
 * 后 iOS 不再支持 PCBC (与鸿蒙端一致); 书源脚本极少使用 PCBC, 属可接受回退。
 *
 * 注意: mbedTLS 目标码需 mac 侧编 libmbedtls.a 链入 (见 shared/src/cinterop/mbedtls/README.md);
 * 缺 .a 是链接期失败而非运行期。
 *
 * 行为字节级对齐 jvmAndAndroidMain (javax.crypto / hutool)。
 */
actual object NativeDigestOps {

    actual fun digest(algorithm: String, data: ByteArray): ByteArray =
        MbedTlsOps.digest(algorithm, data)
}

actual object NativeHmacOps {

    actual fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray =
        MbedTlsOps.hmac(algorithm, key, data)
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
    ): ByteArray = MbedTlsOps.cipherEncrypt("AES", mode, padding, key, iv, data)

    actual fun decrypt(
        key: ByteArray,
        data: ByteArray,
        mode: String,
        padding: String,
        iv: ByteArray?
    ): ByteArray = MbedTlsOps.cipherDecrypt("AES", mode, padding, key, iv, data)

    /** mbedTLS CTR_DRBG 熵源生成随机 AES 密钥 (对齐 hutool KeyUtil.generateKey 的 SecureRandom 语义)。 */
    actual fun randomKey(size: Int): ByteArray = MbedTlsOps.random(size)
}
