package io.legado.app.help

import cn.hutool.crypto.digest.DigestUtil
import cn.hutool.crypto.digest.HMac
import io.legado.app.help.crypto.AsymmetricCrypto
import io.legado.app.help.crypto.AsymmetricCryptoAndroid
import io.legado.app.help.crypto.Sign
import io.legado.app.help.crypto.SignAndroid
import io.legado.app.help.crypto.SymmetricCrypto
import io.legado.app.help.crypto.SymmetricCryptoAndroid
import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.MD5Utils


/**
 * js加解密扩展类, 在js中通过java变量调用
 * 添加方法，请更新文档/legado/app/src/main/assets/help/JsHelp.md
 * 牵 app crypto 包的 createSymmetricCrypto/createAsymmetricCrypto/createSign 在 [JsCryptoProviderJvm]
 *
 * KMP actual interface: 纯 abstract (与 commonMain expect 的 abstract modality 对齐)。
 * 默认实现 (hutool + Base64Lenient) 移至 [JsEncodeUtilsDefaults] interface,
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
 * jvmAndAndroidMain 端 [JsEncodeUtils] 默认实现 (hutool + Base64Lenient)。
 *
 * KMP 限制: actual interface 成员 modality 必须与 expect 一致 (abstract), 不能带方法体;
 * 故将默认实现下沉到独立的 Defaults interface, 由调用方多继承注入:
 * - [JsCryptoProviderJvm] (JS 加解密面的平台实现入口)
 * - jvmAndAndroidTest `object : JsEncodeUtilsDefaults {}` (替代原 `object : JsEncodeUtils {}`)
 *
 * 加密工厂 (createSymmetricCrypto/createAsymmetricCrypto/createSign) 在 [JsCryptoProviderJvm]
 * (实现类 AsymmetricCryptoAndroid/SignAndroid/SymmetricCryptoAndroid 与 hutool 返回类型同源集可见)。
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
        // 原 android.util.Base64.NO_WRAP: 标准字母表+padding+不换行
        return Base64Lenient.encodeToString(DigestUtil.digester(algorithm).digest(data))
    }

    @Suppress("FunctionName")
    override fun HMacHex(data: String, algorithm: String, key: String): String {
        return HMac(algorithm, key.toByteArray()).digestHex(data)
    }

    @Suppress("FunctionName")
    override fun HMacBase64(data: String, algorithm: String, key: String): String {
        // 原 android.util.Base64.NO_WRAP: 标准字母表+padding+不换行
        return Base64Lenient.encodeToString(HMac(algorithm, key.toByteArray()).digest(data))
    }

}

/**
 * JS 加解密面 (commonMain [JsCryptoProvider]) 的 jvmAndAndroid 实现。
 * 摘要/HMAC 复用 [JsEncodeUtilsDefaults]; 工厂返回 commonMain crypto 接口
 * (SymmetricCryptoAndroid 等实现类同时实现 commonMain 接口, 跨端引用透明)。
 *
 * key 为 null 时 hutool 使用随机密钥; iv 非空时 hutool setIv 原地设置后返回自身。
 */
object JsCryptoProviderJvm : JsCryptoProvider, JsEncodeUtilsDefaults {

    override fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray?,
        iv: ByteArray?
    ): SymmetricCrypto {
        val crypto = SymmetricCryptoAndroid(transformation, key)
        if (iv != null && iv.isNotEmpty()) crypto.setIv(iv)
        return crypto
    }

    override fun createAsymmetricCrypto(
        transformation: String
    ): AsymmetricCrypto {
        return AsymmetricCryptoAndroid(transformation)
    }

    override fun createSign(
        algorithm: String
    ): Sign {
        return SignAndroid(algorithm)
    }
}
