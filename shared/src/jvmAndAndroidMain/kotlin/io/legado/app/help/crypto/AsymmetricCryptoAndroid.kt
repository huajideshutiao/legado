package io.legado.app.help.crypto

import cn.hutool.crypto.KeyUtil
import cn.hutool.crypto.asymmetric.KeyType
import io.legado.app.utils.EncoderUtils
import java.io.InputStream


// KMP 化: 原 `class AsymmetricCrypto` 改名为 `AsymmetricCryptoAndroid` (仿 SymmetricCryptoAndroid
// 命名约定), 避免与 commonMain 同包同名 interface AsymmetricCrypto 在合并编译时冲突
// (Kotlin 规则: 同包 class-like declarations 不能同名)。
// 方法体零变化: 仅追加 `: AsymmetricCrypto` (commonMain interface) 实现标记 + 方法 `override`。
// 例外: 原 @JvmOverloads + `usePublicKey: Boolean? = true` 默认值生成的 5 个单参 JVM 重载
// (decrypt/decryptStr/encrypt/encryptHex/encryptBase64) 在 override 化后丢失——Kotlin 禁止
// override 声明参数默认值, @JvmOverloads 无从生成 1 参方法, JS 桥反射按 1 参调用即报
// "Cannot find method ... with args [String]"。已显式补回 (见文件底部 JVM-only 单参重载)。
//
// @Keep 移除：shared 无 androidx.annotation 依赖，JS 桥反射保活改由 consumer-rules.pro -keep 登记（照 JsURL/StrResponse 先例）。
@Suppress("unused")
class AsymmetricCryptoAndroid(algorithm: String) : cn.hutool.crypto.asymmetric.AsymmetricCrypto(algorithm), AsymmetricCrypto {

    @Suppress("MemberVisibilityCanBePrivate")
    override fun setPrivateKey(key: ByteArray): AsymmetricCryptoAndroid {
        setPrivateKey(
            KeyUtil.generatePrivateKey(this.algorithm, key)
        )
        return this
    }

    override fun setPrivateKey(key: String): AsymmetricCryptoAndroid = setPrivateKey(key.encodeToByteArray())

    @Suppress("MemberVisibilityCanBePrivate")
    override fun setPublicKey(key: ByteArray): AsymmetricCryptoAndroid {
        setPublicKey(
            KeyUtil.generatePublicKey(this.algorithm, key)
        )
        return this
    }

    override fun setPublicKey(key: String): AsymmetricCryptoAndroid = setPublicKey(key.encodeToByteArray())

    private fun getKeyType(usePublicKey: Boolean? = true): KeyType {
        return when (usePublicKey) {
            true -> KeyType.PublicKey
            else -> KeyType.PrivateKey
        }
    }

    override fun decrypt(data: Any, usePublicKey: Boolean?): ByteArray {
        return when (data) {
            is ByteArray -> decrypt(data, getKeyType(usePublicKey))
            is String -> decrypt(data, getKeyType(usePublicKey))
            is InputStream -> decrypt(data, getKeyType(usePublicKey))
            else -> throw IllegalArgumentException("Unexpected input type")
        }
    }

    override fun decryptStr(data: Any, usePublicKey: Boolean?): String {
        return when (data) {
            is ByteArray -> String(decrypt(data, getKeyType(usePublicKey)))
            is String -> decryptStr(data, getKeyType(usePublicKey))
            is InputStream -> String(decrypt(data, getKeyType(usePublicKey)))
            else -> throw IllegalArgumentException("Unexpected input type")
        }
    }

    override fun encrypt(data: Any, usePublicKey: Boolean?): ByteArray {
        return when (data) {
            is ByteArray -> encrypt(data, getKeyType(usePublicKey))
            is String -> encrypt(data, getKeyType(usePublicKey))
            is InputStream -> encrypt(data, getKeyType(usePublicKey))
            else -> throw IllegalArgumentException("Unexpected input type")
        }
    }

    override fun encryptHex(data: Any, usePublicKey: Boolean?): String {
        return when (data) {
            is ByteArray -> encryptHex(data, getKeyType(usePublicKey))
            is String -> encryptHex(data, getKeyType(usePublicKey))
            is InputStream -> encryptHex(data, getKeyType(usePublicKey))
            else -> throw IllegalArgumentException("Unexpected input type")
        }
    }

    override fun encryptBase64(data: Any, usePublicKey: Boolean?): String {
        return EncoderUtils.base64Encode(encrypt(data, usePublicKey))
    }

    // ============ JVM-only 单参重载 ============
    // 原版 @JvmOverloads 语义还原: `usePublicKey: Boolean? = true` 缺省即公钥加密/解密。
    // 书源 JS 按 `java.createAsymmetricCrypto(...).encryptHex(str)` 单参调用
    // (hutool 父类仅 KeyType 双参变体, 无 1 参方法), 缺了这些重载 JavaObjectBridge
    // 反射 findMethod 返回 null → IllegalStateException。
    // 不进 commonMain interface (native 端 NativeJsExtensionsBridge 自有单参分派,
    // 仿 SymmetricCryptoAndroid JVM-only 附加成员先例)。
    fun decrypt(data: Any): ByteArray = decrypt(data, true)

    fun decryptStr(data: Any): String = decryptStr(data, true)

    fun encrypt(data: Any): ByteArray = encrypt(data, true)

    fun encryptHex(data: Any): String = encryptHex(data, true)

    fun encryptBase64(data: Any): String = encryptBase64(data, true)

}
