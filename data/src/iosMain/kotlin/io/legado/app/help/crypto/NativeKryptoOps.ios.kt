@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

/**
 * iOS actual: 摘要 / HMAC / AES。
 *
 * 平台委托 (2026-10 起 native 不再带 mbedTLS):
 * - MD5/SHA1/SHA224/256/384/512: CommonCrypto ([IosCommonCrypto]);
 * - SM3/SHA3-* 及其 HMAC: 纯 Kotlin 侧车 ([KotlinDigestNative]/[Sha3Native]), 双端同源;
 * - AES 全模式: [PlatformCipherOps] (CommonCrypto CCCryptorCreateWithMode),
 *   AES/GCM 由 NativeSymmetricCrypto 路由到纯 Kotlin [GcmNative];
 * - 随机密钥: SecRandomCopyBytes (Security.framework)。
 *
 * 行为字节级对齐 jvmAndAndroidMain (javax.crypto / hutool)。
 */
actual object NativeDigestOps {

    actual fun digest(algorithm: String, data: ByteArray): ByteArray {
        KotlinDigestNative.digest(algorithm, data)?.let { return it }
        return IosCommonCrypto.digest(KotlinDigestNative.normalize(algorithm), data)
    }
}

actual object NativeHmacOps {

    actual fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        KotlinDigestNative.hmac(algorithm, key, data)?.let { return it }
        return IosCommonCrypto.hmac(KotlinDigestNative.normalize(KotlinDigestNative.stripHmac(algorithm)), key, data)
    }
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
    ): ByteArray = PlatformCipherOps.encrypt(
        "AES", mode, key, iv, SymPadding.pad(padding, data, 16, mode)
    )

    actual fun decrypt(
        key: ByteArray,
        data: ByteArray,
        mode: String,
        padding: String,
        iv: ByteArray?
    ): ByteArray = SymPadding.unpad(
        padding,
        PlatformCipherOps.decrypt("AES", mode, key, iv, data),
        16
    )

    /** SecRandomCopyBytes 系统熵源 (对齐 hutool KeyUtil.generateKey 的 SecureRandom 语义)。 */
    actual fun randomKey(size: Int): ByteArray = PlatformCipherOps.randomBytes(size)
}
