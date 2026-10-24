@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

/**
 * iOS actual: 对称 NoPadding 裸原语 (CommonCrypto CCCryptorCreateWithMode) + 熵源 (SecRandomCopyBytes)。
 *
 * padding 由 nativeMain [SymPadding] 处理, 本层恒 NoPadding; GCM 不经本层 (纯 Kotlin [GcmNative],
 * 经 [aesEcbEncryptBlock] 取单块原语)。
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual object PlatformCipherOps {

    actual fun encrypt(
        algorithm: String,
        mode: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray = IosCommonCrypto.crypt(encrypt = true, algorithm, mode, key, iv, data)

    actual fun decrypt(
        algorithm: String,
        mode: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray = IosCommonCrypto.crypt(encrypt = false, algorithm, mode, key, iv, data)

    /** AES-ECB 单块加密 (16 字节), 供纯 Kotlin [GcmNative] 的 CTR/GHASH 使用。 */
    actual fun aesEcbEncryptBlock(key: ByteArray, block: ByteArray): ByteArray =
        IosCommonCrypto.crypt(encrypt = true, "AES", "ECB", key, null, block)

    actual fun randomBytes(size: Int): ByteArray = IosCommonCrypto.randomBytes(size)
}
