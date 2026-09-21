package io.legado.app.help.crypto

/**
 * [MbedTlsOps] 的 actual: 转发到同处 leaf 源集的 MbedTls* 五件套。
 *
 * 本文件与 MbedTls*.native.kt 一同被 stage 进各 target 的 leaf (见 build.gradle.kts 的
 * nativeInteropSourcePatterns), 因而能引用 cinterop 绑定; 上层源集只看得到 expect。
 */
internal actual object MbedTlsOps {

    actual fun digest(algorithm: String, data: ByteArray): ByteArray =
        MbedTlsDigest.digest(algorithm, data)

    actual fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray =
        MbedTlsDigest.hmac(algorithm, key, data)

    actual fun cipherEncrypt(
        algorithm: String,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray = MbedTlsCipher.encrypt(algorithm, mode, padding, key, iv, data)

    actual fun cipherDecrypt(
        algorithm: String,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray = MbedTlsCipher.decrypt(algorithm, mode, padding, key, iv, data)

    actual fun sign(algorithm: String, privateKey: ByteArray?, data: ByteArray): ByteArray =
        MbedTlsSign.sign(algorithm, privateKey, data)

    actual fun verify(
        algorithm: String,
        publicKey: ByteArray?,
        data: ByteArray,
        signature: ByteArray
    ): Boolean = MbedTlsSign.verify(algorithm, publicKey, data, signature)

    actual fun rsaEncrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray = MbedTlsRsa.encrypt(algorithm, usePublicKey, privateKey, publicKey, data)

    actual fun rsaDecrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray = MbedTlsRsa.decrypt(algorithm, usePublicKey, privateKey, publicKey, data)

    actual fun random(size: Int): ByteArray = MbedTlsRng.random(size)
}
