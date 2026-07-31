package io.legado.app.help

/**
 * js 加解密扩展类，在 js 中通过 java 变量调用。
 * 添加方法，请更新文档 /legado/app/src/main/assets/help/JsHelp.md
 * 牵 app crypto 包的 createSymmetricCrypto/createAsymmetricCrypto/createSign 在 JsEncodeUtilsDefaults (jvmAndAndroidMain)
 *
 * KMP expect interface：commonMain 仅声明 6 个 abstract 方法（KMP 限制: expect 不允许带方法体）。
 * - 各平台 actual interface 也保持纯 abstract (modality 对齐: expect abstract vs actual abstract)。
 * - 默认实现下沉到各平台的 `JsEncodeUtilsDefaults` interface (jvmAndAndroidMain/iOS/鸿蒙):
 *   - jvmAndAndroidMain: hutool DigestUtil + java.util.Base64 (行为零变化)
 *   - iOS/鸿蒙: krypto MD5/SHA/HMAC + encodeBase64Standard
 *
 * 调用方多继承 [JsEncodeUtilsDefaults] 注入 default 方法体;
 * jvmAndAndroidTest 中 `object : JsEncodeUtilsDefaults {}` 匿名实现继续可用。
 */
@Suppress("unused")
expect interface JsEncodeUtils {

    fun md5Encode(str: String): String

    fun md5Encode16(str: String): String

    //******************消息摘要/散列消息鉴别码************************//

    /**
     * 生成摘要，并转为 16 进制字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @return 16 进制字符串
     */
    fun digestHex(data: String, algorithm: String): String

    /**
     * 生成摘要，并转为 Base64 字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @return Base64 字符串
     */
    fun digestBase64Str(data: String, algorithm: String): String

    /**
     * 生成散列消息鉴别码，并转为 16 进制字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @param key 密钥
     * @return 16 进制字符串
     */
    @Suppress("FunctionName")
    fun HMacHex(data: String, algorithm: String, key: String): String

    /**
     * 生成散列消息鉴别码，并转为 Base64 字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @param key 密钥
     * @return Base64 字符串
     */
    @Suppress("FunctionName")
    fun HMacBase64(data: String, algorithm: String, key: String): String
}
