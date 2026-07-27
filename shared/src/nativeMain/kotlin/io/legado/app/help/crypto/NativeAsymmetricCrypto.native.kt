package io.legado.app.help.crypto

import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.encodeBase64Standard
import io.legado.app.utils.toHexLower

/**
 * nativeMain: 非对称加解密门面 [AsymmetricCrypto] 实现 (iOS / 鸿蒙 两端共用壳)。
 *
 * 本类只做 data/key 归一 + Base64/Hex 编码, 真实加解密下沉到 [NativeAsymmetricCryptoOps]
 * (expect object): iOS 端 Security.framework actual, 鸿蒙端 napi actual。
 *
 * 行为字节级对齐 jvmAndAndroidMain (hutool AsymmetricEncryptor/Decryptor):
 * - setPrivateKey/setPublicKey(String) → encodeToByteArray (UTF-8) 视为 DER (私钥 PKCS#8, 公钥 X.509),
 *   与 hutool KeyUtil.generate*Key(algorithm, key) 一致 (key 即原始 DER 字节)。
 * - encrypt(String) → UTF-8 字节 (hutool encrypt(String) = StrUtil.utf8Bytes, 不解码 Hex/Base64);
 * - decrypt(String) → SecureUtil.decode (Hex 优先, 否则 Base64) 解码为密文字节 (hutool decrypt(String) = SecureUtil.decode);
 * - encryptHex/encryptBase64 = encrypt 后 toHexLower/encodeBase64Standard (对齐 hutool HexUtil/Base64)。
 *
 * usePublicKey 语义 (对齐 jvmAndAndroid getKeyType): true→公钥, false/null→私钥。
 * RSA 标准场景: encrypt(usePublicKey=true) 公钥加密 + decrypt(usePublicKey=false) 私钥解密, 两端 actual 均支持;
 * 私钥加密/公钥解密为罕见场景, iOS Security.framework 不直接支持, 各端 actual 按需抛异常。
 */
class NativeAsymmetricCrypto(
    private val algorithm: String,
) : AsymmetricCrypto {

    private var privateKeyBytes: ByteArray? = null
    private var publicKeyBytes: ByteArray? = null

    override fun setPrivateKey(key: ByteArray): AsymmetricCrypto {
        privateKeyBytes = key
        return this
    }

    override fun setPrivateKey(key: String): AsymmetricCrypto {
        privateKeyBytes = key.encodeToByteArray()
        return this
    }

    override fun setPublicKey(key: ByteArray): AsymmetricCrypto {
        publicKeyBytes = key
        return this
    }

    override fun setPublicKey(key: String): AsymmetricCrypto {
        publicKeyBytes = key.encodeToByteArray()
        return this
    }

    override fun decrypt(data: Any, usePublicKey: Boolean?): ByteArray =
        NativeAsymmetricCryptoOps.decrypt(
            algorithm, usePublicKey ?: true, privateKeyBytes, publicKeyBytes, decodeInput(data)
        )

    override fun decryptStr(data: Any, usePublicKey: Boolean?): String =
        String(decrypt(data, usePublicKey))

    override fun encrypt(data: Any, usePublicKey: Boolean?): ByteArray =
        NativeAsymmetricCryptoOps.encrypt(
            algorithm, usePublicKey ?: true, privateKeyBytes, publicKeyBytes, encodeInput(data)
        )

    override fun encryptHex(data: Any, usePublicKey: Boolean?): String =
        encrypt(data, usePublicKey).toHexLower()

    override fun encryptBase64(data: Any, usePublicKey: Boolean?): String =
        encrypt(data, usePublicKey).encodeBase64Standard()

    /** encrypt 入参: ByteArray 直传, String → UTF-8 字节 (对齐 hutool StrUtil.utf8Bytes)。 */
    private fun encodeInput(data: Any): ByteArray = when (data) {
        is ByteArray -> data
        is String -> data.encodeToByteArray()
        else -> throw IllegalArgumentException("AsymmetricCrypto.encrypt: unsupported data type ${data::class}")
    }

    /** decrypt 入参: ByteArray 直传, String → Hex 优先否则 Base64 (对齐 hutool SecureUtil.decode)。 */
    private fun decodeInput(data: Any): ByteArray = when (data) {
        is ByteArray -> data
        is String -> if (hexRegex.matches(data)) decodeHex(data) else Base64Lenient.decode(data)
        else -> throw IllegalArgumentException("AsymmetricCrypto.decrypt: unsupported data type ${data::class}")
    }

    private fun decodeHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "invalid hex length: ${hex.length}" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    companion object {
        private val hexRegex = Regex("^[0-9a-fA-F]+$")
    }
}

/**
 * native 端非对称加解密真实实现下沉点 (expect object)。
 *
 * - iOS actual: Security.framework (SecKeyCreateEncryptedData/DecryptedData + SecKeyCreateWithData);
 * - 鸿蒙 actual: ohos.security.cryptoAsymCipher napi (或 stub 抛异常 + TODO)。
 *
 * usePublicKey: true→用公钥, false/null→用私钥 (对齐 jvmAndAndroid KeyType)。
 * privateKey/publicKey: DER 字节 (私钥 PKCS#8, 公钥 X.509)。
 */
expect object NativeAsymmetricCryptoOps {
    fun encrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray

    fun decrypt(
        algorithm: String,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray
}
