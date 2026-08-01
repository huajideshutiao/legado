package io.legado.app.help.crypto

/**
 * mbedTLS 主实现的纯 Kotlin 门面。
 *
 * MbedTls* 五件套直接引用 cinterop 绑定, 而 cinterop 绑定只在各 target 的 leaf 源集可见
 * (build.gradle.kts 的 nativeInteropSourcePatterns 把它们 stage 进 leaf), 因此
 * nativeMain / iosMain / ohosMain 这些上层源集无法直接引用它们。本 expect 是唯一的跨源集入口:
 * 签名不含 cinterop 类型, actual 随 stage 落在 leaf (见 MbedTlsOps.native.kt)。
 */
internal expect object MbedTlsOps {

    fun digest(algorithm: String, data: ByteArray): ByteArray

    fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray

    fun cipherEncrypt(
        algorithm: String,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray

    fun cipherDecrypt(
        algorithm: String,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray

    fun sign(algorithm: String, privateKey: ByteArray?, data: ByteArray): ByteArray

    fun verify(
        algorithm: String,
        publicKey: ByteArray?,
        data: ByteArray,
        signature: ByteArray
    ): Boolean

    fun rsaEncrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray

    fun rsaDecrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray

    /** 平台安全熵源 (CTR_DRBG) 随机字节。 */
    fun random(size: Int): ByteArray
}

/**
 * mbedTLS 主实现优先, 任意异常回落 [fallback]; 双失败时抛主实现异常并 suppress 回落异常。
 *
 * 纯 Kotlin 无 cinterop 依赖, 放在 nativeMain 供 iosMain/ohosMain 的 actual 复用
 * (原先与 MbedTlsRng 同文件, 被 stage 进 leaf 后上层源集就看不到了)。
 */
internal inline fun <T> mbedTlsOrFallback(primary: () -> T, fallback: () -> T): T = try {
    primary()
} catch (primaryError: Throwable) {
    try {
        fallback()
    } catch (fallbackError: Throwable) {
        primaryError.addSuppressed(fallbackError)
        throw primaryError
    }
}
