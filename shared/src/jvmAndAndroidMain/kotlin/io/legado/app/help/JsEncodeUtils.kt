package io.legado.app.help

import cn.hutool.crypto.digest.DigestUtil
import cn.hutool.crypto.digest.HMac
import cn.hutool.crypto.symmetric.SymmetricCrypto
import io.legado.app.help.crypto.AsymmetricCrypto
import io.legado.app.help.crypto.AsymmetricCryptoAndroid
import io.legado.app.help.crypto.Sign
import io.legado.app.help.crypto.SignAndroid
import io.legado.app.help.crypto.SymmetricCryptoAndroid
import io.legado.app.utils.MD5Utils
import java.util.Base64


/**
 * js加解密扩展类, 在js中通过java变量调用
 * 添加方法，请更新文档/legado/app/src/main/assets/help/JsHelp.md
 * 牵 app crypto 包的 createSymmetricCrypto/createAsymmetricCrypto/createSign 在 JsEncodeUtilsAndroid
 *
 * KMP actual interface: 纯 abstract (与 commonMain expect 的 abstract modality 对齐)。
 * 默认实现 (hutool + java.util.Base64) 移至 [JsEncodeUtilsDefaults] interface,
 * 由调用方多继承注入, jvmAndAndroidTest `object : JsEncodeUtilsDefaults {}` 可复用。
 *
 * 行为零变化: 原 actual interface 方法体原样搬到 Defaults, 仅是 host 类型变化。
 */
@Suppress("unused")
actual interface JsEncodeUtils {

    actual fun md5Encode(str: String): String

    actual fun md5Encode16(str: String): String

    //******************消息摘要/散列消息鉴别码************************//

    /**
     * 生成摘要，并转为16进制字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @return 16进制字符串
     */
    actual fun digestHex(data: String, algorithm: String): String

    /**
     * 生成摘要，并转为Base64字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @return Base64字符串
     */
    actual fun digestBase64Str(data: String, algorithm: String): String

    /**
     * 生成散列消息鉴别码，并转为16进制字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @param key 密钥
     * @return 16进制字符串
     */
    @Suppress("FunctionName")
    actual fun HMacHex(data: String, algorithm: String, key: String): String

    /**
     * 生成散列消息鉴别码，并转为Base64字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @param key 密钥
     * @return Base64字符串
     */
    @Suppress("FunctionName")
    actual fun HMacBase64(data: String, algorithm: String, key: String): String
}

/**
 * jvmAndAndroidMain 端 [JsEncodeUtils] 默认实现 (hutool + java.util.Base64)。
 *
 * KMP 限制: actual interface 成员 modality 必须与 expect 一致 (abstract), 不能带方法体;
 * 故将默认实现下沉到独立的 Defaults interface, 由调用方多继承注入:
 * - 各调用方 (BookSourceJsExt/AnalyzeRule 等) : JsEncodeUtilsDefaults (替代原 `: JsEncodeUtils`)
 * - jvmAndAndroidTest `object : JsEncodeUtilsDefaults {}` (替代原 `object : JsEncodeUtils {}`)
 *
 * 行为零变化: 6 个方法体原样搬迁自原 actual interface, 仅 host 类型变化。
 *
 * 加密工厂 (createSymmetricCrypto/createAsymmetricCrypto/createSign) 原在 app/desktop 四处重复
 * (app 端 JsEncodeUtilsAndroid 与 DesktopJsExtensions/DesktopAnalyzeRule/DesktopAnalyzeUrl, 已删),
 * 统一下沉至此 (实现类 AsymmetricCryptoAndroid/SignAndroid/SymmetricCryptoAndroid
 * 与 hutool 返回类型同源集可见)。nativeMain 各有同名接口, 互不影响。
 */
@Suppress("unused")
interface JsEncodeUtilsDefaults : JsEncodeUtils {

    override fun md5Encode(str: String): String {
        return MD5Utils.md5Encode(str)
    }

    override fun md5Encode16(str: String): String {
        return MD5Utils.md5Encode16(str)
    }

    override fun digestHex(data: String, algorithm: String): String {
        return DigestUtil.digester(algorithm).digestHex(data)
    }

    override fun digestBase64Str(data: String, algorithm: String): String {
        // 原 android.util.Base64.NO_WRAP: 标准字母表+padding+不换行 = java.util.Base64.getEncoder()
        return Base64.getEncoder().encodeToString(DigestUtil.digester(algorithm).digest(data))
    }

    @Suppress("FunctionName")
    override fun HMacHex(data: String, algorithm: String, key: String): String {
        return HMac(algorithm, key.toByteArray()).digestHex(data)
    }

    @Suppress("FunctionName")
    override fun HMacBase64(data: String, algorithm: String, key: String): String {
        // 原 android.util.Base64.NO_WRAP: 标准字母表+padding+不换行 = java.util.Base64.getEncoder()
        return Base64.getEncoder().encodeToString(HMac(algorithm, key.toByteArray()).digest(data))
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
        return AsymmetricCryptoAndroid(transformation)
    }

    //******************签名************************//
    fun createSign(
        algorithm: String
    ): Sign {
        return SignAndroid(algorithm)
    }
}
