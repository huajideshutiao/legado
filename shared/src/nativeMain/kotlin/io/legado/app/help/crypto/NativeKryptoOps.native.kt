package io.legado.app.help.crypto

/**
 * native 端摘要 / HMAC / AES 真实实现下沉点 (expect object)。
 *
 * 两端 actual 均为 mbedTLS 主实现 (MbedTlsDigest/MbedTlsCipher/MbedTlsRng, 见 mbedTlsOrFallback),
 * 任意异常回落既有实现:
 * - iOS 回落: krypto 库 (korlibs.crypto) + CommonCrypto (SHA-224/384);
 * - 鸿蒙回落: @ohos.security.cryptoFramework napi 桥接 (仅 AES ECB+PKCS7)。
 *
 * # 存在意义
 * 平台差异 (krypto 无 ohosArm64 变体 / napi 桥能力窄) 收敛在 actual 层, nativeMain 5 个消费方
 * (CryptoHelper / BackupAES / NativeSymmetricCrypto / JsEncodeUtils / JsExtensionsPlatform)
 * 仅调用 expect 接口。
 *
 * # 算法归一化
 * - digest: 接受 MD5/SHA-1/SHA-224/SHA-256/SHA-384/SHA-512/RIPEMD160 及无连字符变体 (大小写不敏感)
 * - hmac: 接受 HmacMD5/HmacSHA1/.../HmacSHA512 及连字符变体 (HMAC-MD5/HMAC-SHA256 等)
 * - aes: encrypt/decrypt 接收归一化 mode (ECB/CBC/PCBC/CFB/OFB/CTR) 与 padding
 *   (NOPADDING/PKCS7PADDING/ANSIX923PADDING/ISO10126PADDING/ZEROPADDING) token,
 *   由 [NativeSymmetricCrypto] 解析 transformation 后传入; PCBC 无 mbedTLS 实现,
 *   iOS 回落 krypto 可用, 鸿蒙抛异常; AES/GCM 与 DES/DESede 不走本 expect, 见 MbedTlsCipher。
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
