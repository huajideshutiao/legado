package io.legado.app.help.crypto

/**
 * mbedTLS cipher 层对称加解密的跨源集门面 (对齐 [NativeMbedTlsOps] 的 expect/actual 模式)。
 *
 * [MbedTlsCipher] 直接引用 mbedtls cinterop 绑定, 而绑定只在各 target 的 leaf 源集可见
 * (build.gradle.kts 的 nativeInteropSourcePatterns 把 MbedTls*.native.kt stage 进 leaf),
 * nativeMain 的 [NativeSymmetricCrypto] 无法直接引用它, 经本 expect 门面跨源集调用:
 * expect 在 nativeMain (签名不含 cinterop 类型), actual 随 [MbedTlsCipher] 落在 leaf。
 */
internal expect object MbedTlsCipherOps {

    fun encrypt(
        algorithm: String,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray

    fun decrypt(
        algorithm: String,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray
}
