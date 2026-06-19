package io.legado.app.help

import android.util.Base64
import cn.hutool.crypto.digest.DigestUtil
import cn.hutool.crypto.digest.HMac
import cn.hutool.crypto.symmetric.SymmetricCrypto
import io.legado.app.help.crypto.AsymmetricCrypto
import io.legado.app.help.crypto.Sign
import io.legado.app.help.crypto.SymmetricCryptoAndroid
import io.legado.app.utils.MD5Utils


/**
 * js加解密扩展类, 在js中通过java变量调用
 * 添加方法，请更新文档/legado/app/src/main/assets/help/JsHelp.md
 */
@Suppress("unused")
interface JsEncodeUtils {

    fun md5Encode(str: String): String {
        return MD5Utils.md5Encode(str)
    }

    fun md5Encode16(str: String): String {
        return MD5Utils.md5Encode16(str)
    }


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
        return AsymmetricCrypto(transformation)
    }

    //******************签名************************//
    fun createSign(
        algorithm: String
    ): Sign {
        return Sign(algorithm)
    }
//******************消息摘要/散列消息鉴别码************************//

    /**
     * 生成摘要，并转为16进制字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @return 16进制字符串
     */
    fun digestHex(
        data: String,
        algorithm: String,
    ): String {
        return DigestUtil.digester(algorithm).digestHex(data)
    }

    /**
     * 生成摘要，并转为Base64字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @return Base64字符串
     */
    fun digestBase64Str(
        data: String,
        algorithm: String,
    ): String {
        return Base64.encodeToString(DigestUtil.digester(algorithm).digest(data), Base64.NO_WRAP)
    }

    /**
     * 生成散列消息鉴别码，并转为16进制字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @param key 密钥
     * @return 16进制字符串
     */
    @Suppress("FunctionName")
    fun HMacHex(
        data: String,
        algorithm: String,
        key: String
    ): String {
        return HMac(algorithm, key.toByteArray()).digestHex(data)
    }

    /**
     * 生成散列消息鉴别码，并转为Base64字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @param key 密钥
     * @return Base64字符串
     */
    @Suppress("FunctionName")
    fun HMacBase64(
        data: String,
        algorithm: String,
        key: String
    ): String {
        return Base64.encodeToString(
            HMac(algorithm, key.toByteArray()).digest(data),
            Base64.NO_WRAP
        )
    }


}