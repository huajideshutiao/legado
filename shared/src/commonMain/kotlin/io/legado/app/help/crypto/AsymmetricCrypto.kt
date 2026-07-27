package io.legado.app.help.crypto

/**
 * KMP 抽象的非对称加解密门面。
 *
 * JVM/Android 半区 actual 由 `jvmAndAndroidMain/io/legado/app/help/crypto/AsymmetricCrypto.kt`
 * 中的 `AsymmetricCrypto` 类实现，继承 hutool `cn.hutool.crypto.asymmetric.AsymmetricCrypto`
 * 提供完整能力；iOS / 鸿蒙 半区可提供 actual 实现或抛 UnsupportedOperationException 降级。
 *
 * 接口签名对齐 jvmAndAndroidMain 既有 public 方法，不暴露 hutool 类型，保证调用方
 * （JsEncodeUtilsAndroid 等）跨端引用透明兼容。jvmAndAndroidMain 实现类仅追加
 * `: AsymmetricCrypto` 标记，方法体零变化。
 *
 * 注：interface 方法不定义默认值，由各 actual 实现类自行指定（jvmAndAndroidMain 保留
 * 原 `@JvmOverloads` + `usePublicKey: Boolean? = true` 默认值，行为零变化）。
 * Kotlin 规则：override 方法不能重新定义默认值，故 interface 不写默认值。
 */
interface AsymmetricCrypto {

    fun setPrivateKey(key: ByteArray): AsymmetricCrypto

    fun setPrivateKey(key: String): AsymmetricCrypto

    fun setPublicKey(key: ByteArray): AsymmetricCrypto

    fun setPublicKey(key: String): AsymmetricCrypto

    /**
     * 解密。data 可为 ByteArray / String / InputStream（JVM 半区）。
     * usePublicKey=true 用公钥解密，false/null 用私钥解密。
     */
    fun decrypt(data: Any, usePublicKey: Boolean?): ByteArray

    fun decryptStr(data: Any, usePublicKey: Boolean?): String

    /**
     * 加密。data 可为 ByteArray / String / InputStream（JVM 半区）。
     * usePublicKey=true 用公钥加密，false/null 用私钥加密。
     */
    fun encrypt(data: Any, usePublicKey: Boolean?): ByteArray

    fun encryptHex(data: Any, usePublicKey: Boolean?): String

    fun encryptBase64(data: Any, usePublicKey: Boolean?): String
}
