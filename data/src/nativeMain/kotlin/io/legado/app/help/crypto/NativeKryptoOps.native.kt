package io.legado.app.help.crypto

/**
 * native 端摘要 / HMAC / AES 真实实现下沉点 (expect object)。
 *
 * 平台委托 (2026-10 起 native 不再带 mbedTLS):
 * - iOS: CommonCrypto (摘要/HMAC/AES) + Security.framework (RSA/签名) + 纯 Kotlin 侧车;
 * - 鸿蒙: @ohos.security.cryptoFramework napi 桥 (摘要/HMAC/AES/RSA/SM2) + /dev/urandom + 纯 Kotlin 侧车;
 * - 纯 Kotlin 侧车 (KotlinDigestNative/Sha3Native): SM3/SHA3-* 及其 HMAC, 双端同源。
 *
 * # 存在意义
 * 平台差异收敛在 actual 层, nativeMain 消费方
 * (CryptoHelper / BackupAES / NativeSymmetricCrypto / JsEncodeUtils / JsExtensionsPlatform)
 * 仅调用 expect 接口。
 *
 * # 算法归一化
 * - digest: 接受 MD5/SHA-1/SHA-224/SHA-256/SHA-384/SHA-512/SM3/SHA3-224/256/384/512
 *   及无连字符变体 (大小写不敏感); 侧车算法见 KotlinDigestNative
 * - hmac: 接受 HmacMD5/HmacSHA1/.../HmacSHA512 及连字符变体 (HMAC-MD5/HMAC-SHA256 等)
 * - aes: encrypt/decrypt 接收归一化 mode (ECB/CBC/PCBC/CFB/OFB/CTR) 与 padding
 *   (NOPADDING/PKCS7PADDING/ANSIX923PADDING/ISO10126PADDING/ZEROPADDING) token,
 *   由 [NativeSymmetricCrypto] 解析 transformation 后传入; PCBC 无平台实现,
 *   两端均抛异常; AES/GCM 由 NativeSymmetricCrypto 路由到纯 Kotlin GcmNative。
 *
 * 行为字节级对齐 jvmAndAndroidMain (javax.crypto / hutool)。
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

    fun encrypt(key: ByteArray, data: ByteArray, mode: String, padding: String, iv: ByteArray?): ByteArray

    fun decrypt(key: ByteArray, data: ByteArray, mode: String, padding: String, iv: ByteArray?): ByteArray

    /** 平台安全熵源生成随机 AES 密钥 (对齐 hutool KeyUtil.generateKey 的 SecureRandom 语义)。 */
    fun randomKey(size: Int): ByteArray
}
