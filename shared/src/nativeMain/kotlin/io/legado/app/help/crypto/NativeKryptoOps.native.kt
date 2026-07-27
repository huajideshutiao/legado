package io.legado.app.help.crypto

/**
 * native 端摘要 / HMAC / AES-ECB-PKCS7 真实实现下沉点 (expect object)。
 *
 * - iOS actual: krypto 库 (com.soywiz.krypto) 纯 Kotlin 实现;
 * - 鸿蒙 actual: @ohos.security.cryptoFramework napi 桥接 (OhosNativeBridge.invokeCryptoSync)。
 *
 * # 存在意义
 * krypto 4.0.10 未发布 ohosArm64 变体, 鸿蒙端无法直接依赖 krypto。
 * 将 krypto 调用从 nativeMain 下沉到 iosMain actual, ohosMain 通过 napi 桥接 ArkTS cryptoFramework,
 * nativeMain 5 个消费方 (CryptoHelper / BackupAES / NativeSymmetricCrypto / JsEncodeUtils /
 * JsExtensionsPlatform) 仅调用 expect 接口, 不直接依赖 krypto。
 *
 * # 算法归一化
 * - digest: 接受 MD5/SHA-1/SHA1/SHA-256/SHA256/SHA-512/SHA512 (大小写/连字符不敏感)
 * - hmac: 接受 HmacMD5/HmacSHA1/HmacSHA256/HmacSHA512 及连字符变体 (HMAC-MD5/HMAC-SHA256 等)
 * - aes: 固定 AES/ECB/PKCS7Padding (PKCS5 在 AES 块大小 16 下与 PKCS7 等价)
 *
 * 行为字节级对齐 jvmAndAndroidMain (javax.crypto / hutool) 与 iOS krypto actual。
 */
expect object NativeDigestOps {
    fun digest(algorithm: String, data: ByteArray): ByteArray
}

expect object NativeHmacOps {
    fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray
}

expect object NativeAesOps {
    fun encryptEcbPkcs7(key: ByteArray, data: ByteArray): ByteArray

    fun decryptEcbPkcs7(key: ByteArray, data: ByteArray): ByteArray
}
