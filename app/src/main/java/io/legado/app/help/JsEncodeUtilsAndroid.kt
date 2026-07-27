package io.legado.app.help

import cn.hutool.crypto.symmetric.SymmetricCrypto
import io.legado.app.help.crypto.AsymmetricCrypto
import io.legado.app.help.crypto.AsymmetricCryptoAndroid
import io.legado.app.help.crypto.Sign
import io.legado.app.help.crypto.SignAndroid
import io.legado.app.help.crypto.SymmetricCryptoAndroid

/**
 * JsEncodeUtils 的 app 侧扩展: 依赖 app help/crypto 包的工厂方法。
 * 纯 hutool/摘要面在 shared JsEncodeUtilsDefaults, JS 可见方法集不变。
 *
 * KMP 化: 工厂方法返回类型从 jvmAndAndroidMain 具体类改为 commonMain interface
 * (AsymmetricCrypto/Sign/SymmetricCrypto), 跨端引用透明兼容; JVM 半区 actual 实现类
 * (AsymmetricCryptoAndroid/SignAndroid/SymmetricCryptoAndroid) 仅在工厂方法内部使用。
 *
 * 继承 JsEncodeUtilsDefaults (而非 JsEncodeUtils): KMP 限制 actual interface 成员必须 abstract,
 * 默认实现 (hutool + java.util.Base64) 由 jvmAndAndroidMain 的 JsEncodeUtilsDefaults interface 提供。
 */
@Suppress("unused")
interface JsEncodeUtilsAndroid : JsEncodeUtilsDefaults {

    //******************对称加密解密************************//

    /**
     * 在js中这样使用
     * java.createSymmetricCrypto(transformation, key, iv).decrypt(data)
     * java.createSymmetricCrypto(transformation, key, iv).decryptStr(data)

     * java.createSymmetricCrypto(transformation, key, iv).encrypt(data)
     * java.createSymmetricCrypto(transformation, key, iv).encryptBase64(data)
     * java.createSymmetricCrypto(transformation, key, iv).encryptHex(data)
     */

    /* 调用SymmetricCrypto key为null时使用随机密钥*/
    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray?,
        iv: ByteArray?
    ): SymmetricCrypto {
        val symmetricCrypto = SymmetricCryptoAndroid(transformation, key)
        return if (iv != null && iv.isNotEmpty()) symmetricCrypto.setIv(iv) else symmetricCrypto
    }

    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray
    ): SymmetricCrypto {
        return createSymmetricCrypto(transformation, key, null)
    }

    fun createSymmetricCrypto(
        transformation: String,
        key: String
    ): SymmetricCrypto {
        return createSymmetricCrypto(transformation, key, null)
    }

    fun createSymmetricCrypto(
        transformation: String,
        key: String,
        iv: String?
    ): SymmetricCrypto {
        return createSymmetricCrypto(
            transformation, key.encodeToByteArray(), iv?.encodeToByteArray()
        )
    }

    //******************非对称加密解密************************//

    /* keys都为null时使用随机密钥 */
    fun createAsymmetricCrypto(
        transformation: String
    ): AsymmetricCrypto {
        return AsymmetricCryptoAndroid(transformation)
    }

    //******************签名************************//
    fun createSign(
        algorithm: String
    ): Sign {
        return SignAndroid(algorithm)
    }

}
