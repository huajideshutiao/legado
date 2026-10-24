package io.legado.app.help.crypto

import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.decodeHex
import io.legado.app.utils.isHexString
import io.legado.app.utils.toHexLower

/**
 * nativeMain: 非对称加解密门面 [AsymmetricCrypto] 实现 (iOS / 鸿蒙 两端共用壳)。
 *
 * 本类只做 data/key 归一 + Base64/Hex 编码, 真实加解密下沉到 [NativeAsymmetricCryptoOps]
 * (expect object): iOS=Security.framework, 鸿蒙=cryptoFramework napi 桥 (平台委托, 无 mbedTLS)。
 *
 * 行为字节级对齐 jvmAndAndroidMain (hutool AsymmetricEncryptor/Decryptor):
 * - setPrivateKey/setPublicKey(String) → encodeToByteArray (UTF-8) 视为 DER (私钥 PKCS#8, 公钥 X.509),
 *   与 hutool KeyUtil.generate*Key(algorithm, key) 一致 (key 即原始 DER 字节)。
 * - encrypt(String) → UTF-8 字节 (hutool encrypt(String) = StrUtil.utf8Bytes, 不解码 Hex/Base64);
 * - decrypt(String) → SecureUtil.decode (Hex 优先, 否则 Base64) 解码为密文字节 (hutool decrypt(String) = SecureUtil.decode);
 * - encryptHex/encryptBase64 = encrypt 后 toHexLower/Base64Lenient.encodeToString (对齐 hutool HexUtil/Base64)。
 *
 * usePublicKey 语义 (对齐 jvmAndAndroid getKeyType): true→公钥, false/null→私钥。
 * RSA 仅正向 (公钥加密/私钥解密) 平台可用 (v1.5 + OAEP); 私钥加密/公钥解密反向
 * (hutool KeyType 反向) 与 NoPadding 裸 RSA: iOS Security.framework 与鸿蒙 cryptoFramework
 * 均无此操作 (鸿蒙 NoPadding 例外: cryptoFramework API 9+ 支持输入恰为模长字节的裸 RSA)。
 */
class NativeAsymmetricCrypto(
    transformation: String,
) : AsymmetricCrypto {

    /** transformation 归一 (四端唯一解析点 commonMain [AsymCipherSpec])。 */
    internal val spec: AsymCipherSpec = AsymCipherSpec.parse(transformation)

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
            spec, usePublicKey ?: true, privateKeyBytes, publicKeyBytes, decodeInput(data)
        )

    override fun decryptStr(data: Any, usePublicKey: Boolean?): String =
        decrypt(data, usePublicKey).decodeToString()

    override fun encrypt(data: Any, usePublicKey: Boolean?): ByteArray =
        NativeAsymmetricCryptoOps.encrypt(
            spec, usePublicKey ?: true, privateKeyBytes, publicKeyBytes, encodeInput(data)
        )

    override fun encryptHex(data: Any, usePublicKey: Boolean?): String =
        encrypt(data, usePublicKey).toHexLower()

    override fun encryptBase64(data: Any, usePublicKey: Boolean?): String =
        Base64Lenient.encodeToString(encrypt(data, usePublicKey))

    /** encrypt 入参: ByteArray 直传, String → UTF-8 字节 (对齐 hutool StrUtil.utf8Bytes)。 */
    private fun encodeInput(data: Any): ByteArray = when (data) {
        is ByteArray -> data
        is String -> data.encodeToByteArray()
        else -> throw IllegalArgumentException("AsymmetricCrypto.encrypt: unsupported data type ${data::class}")
    }

    /** decrypt 入参: ByteArray 直传, String → Hex 优先否则 Base64 (对齐 hutool SecureUtil.decode)。 */
    private fun decodeInput(data: Any): ByteArray = when (data) {
        is ByteArray -> data
        is String -> if (data.isHexString()) data.decodeHex() else Base64Lenient.decode(data)
        else -> throw IllegalArgumentException("AsymmetricCrypto.decrypt: unsupported data type ${data::class}")
    }
}

/** 多块输出顺序拼接 (iOS/鸿蒙非对称 actual 共用, 对齐 hutool 分块拼接语义)。 */
internal fun concatBlocks(parts: List<ByteArray>): ByteArray {
    if (parts.size == 1) return parts[0]
    val out = ByteArray(parts.sumOf { it.size })
    var off = 0
    for (p in parts) {
        p.copyInto(out, off)
        off += p.size
    }
    return out
}

/**
 * native 端非对称加解密真实实现下沉点 (expect object)。
 *
 * 两端 actual: iOS Security.framework SecKey*, 鸿蒙 cryptoFramework 桥 (均带 hutool 同款分块)。
 *
 * usePublicKey: true→用公钥, false/null→用私钥 (对齐 jvmAndAndroid KeyType)。
 * privateKey/publicKey: DER 或 PEM 字节, 经 [RsaDerKeys] 归一为各平台要求格式
 * (iOS PKCS#1, 鸿蒙 PKCS#8/SPKI)。
 */
expect object NativeAsymmetricCryptoOps {
    /** [spec] 已由 [AsymCipherSpec.parse] 归一, actual 不再解析 transformation 字符串。 */
    fun encrypt(
        spec: AsymCipherSpec,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray

    fun decrypt(
        spec: AsymCipherSpec,
        usePublicKey: Boolean,
        privateKey: ByteArray?,
        publicKey: ByteArray?,
        data: ByteArray
    ): ByteArray
}
