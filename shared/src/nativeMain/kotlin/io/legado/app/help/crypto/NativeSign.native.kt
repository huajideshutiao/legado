package io.legado.app.help.crypto

import io.legado.app.utils.toHexLower

/**
 * nativeMain: 签名门面 [Sign] 实现 (iOS / 鸿蒙 两端共用壳)。
 *
 * 本类只做 data 归一 + Hex 编码, 真实签名/验签下沉到 [NativeSignOps] (expect object):
 * iOS 端 Security.framework actual, 鸿蒙端 napi actual。
 *
 * 行为字节级对齐 jvmAndAndroidMain (hutool Sign):
 * - setPrivateKey/setPublicKey(String) → encodeToByteArray (UTF-8) 视为 DER (私钥 PKCS#8, 公钥 X.509),
 *   与 hutool KeyUtil.generate*Key 一致。
 * - sign(String) → UTF-8 字节 (hutool sign(String) = StrUtil.bytes(UTF-8)); sign(ByteArray) 直传;
 * - signHex = sign 后 toHexLower (对齐 hutool HexUtil.encodeHexStr, 小写);
 * - verify(data, sign) 用公钥验签, data/sign 均原始字节。
 *
 * 算法对齐 JCA Signature: SHA1/256/384/512withRSA, MD5withRSA (PKCS1 v1.5);
 * SHAxxxWithRSA/PSS (PSS); SHA1/256/384/512withECDSA (ECDSA)。非白名单抛 UnsupportedOperationException。
 *
 * sign 用私钥, verify 用公钥 (对齐 hutool Sign.sign/verify)。
 *
 * 注: commonMain [Sign] interface 仅暴露 key 装载接口; sign/signHex/verify 为 NativeSign 自身方法,
 * 供 JS 桥后续接出 (当前 NativeJsExtensionsBridge __createSignObj 仅接 setPrivateKey/setPublicKey)。
 */
class NativeSign(
    private val algorithm: String,
) : Sign {

    private var privateKeyBytes: ByteArray? = null
    private var publicKeyBytes: ByteArray? = null

    override fun setPrivateKey(key: ByteArray): Sign {
        privateKeyBytes = key
        return this
    }

    override fun setPrivateKey(key: String): Sign {
        privateKeyBytes = key.encodeToByteArray()
        return this
    }

    override fun setPublicKey(key: ByteArray): Sign {
        publicKeyBytes = key
        return this
    }

    override fun setPublicKey(key: String): Sign {
        publicKeyBytes = key.encodeToByteArray()
        return this
    }

    fun sign(data: ByteArray): ByteArray =
        NativeSignOps.sign(algorithm, privateKeyBytes, data)

    fun signHex(data: ByteArray): String = sign(data).toHexLower()

    fun sign(data: String): ByteArray = sign(data.encodeToByteArray())

    fun signHex(data: String): String = sign(data).toHexLower()

    fun verify(data: ByteArray, sign: ByteArray): Boolean =
        NativeSignOps.verify(algorithm, publicKeyBytes, data, sign)

    fun verify(data: String, sign: ByteArray): Boolean =
        verify(data.encodeToByteArray(), sign)
}

/**
 * native 端签名/验签真实实现下沉点 (expect object)。
 *
 * - iOS actual: Security.framework (SecKeyCreateSignature/SecKeyVerifySignature + SecKeyCreateWithData);
 * - 鸿蒙 actual: ohos.security.cryptoFramework napi (或 stub 抛异常 + TODO)。
 *
 * sign 用私钥 (PKCS#8 DER), verify 用公钥 (X.509 DER)。
 */
expect object NativeSignOps {
    fun sign(algorithm: String, privateKey: ByteArray?, data: ByteArray): ByteArray

    fun verify(
        algorithm: String,
        publicKey: ByteArray?,
        data: ByteArray,
        signature: ByteArray
    ): Boolean
}
