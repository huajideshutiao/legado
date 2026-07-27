package io.legado.app.help.crypto

import cn.hutool.crypto.KeyUtil
import cn.hutool.crypto.asymmetric.KeyType
import io.legado.app.utils.EncoderUtils
import java.io.InputStream
import cn.hutool.crypto.asymmetric.AsymmetricCrypto as HutoolAsymmetricCrypto

// KMP 化: 原 `class AsymmetricCrypto` 改名为 `AsymmetricCryptoAndroid` (仿 SymmetricCryptoAndroid
// 命名约定), 避免与 commonMain 同包同名 interface AsymmetricCrypto 在合并编译时冲突
// (Kotlin 规则: 同包 class-like declarations 不能同名)。
// 方法体零变化: 仅追加 `: AsymmetricCrypto` (commonMain interface) 实现标记 + 方法 `override`。
//
// @Keep 移除：shared 无 androidx.annotation 依赖，JS 桥反射保活改由 consumer-rules.pro -keep 登记（照 JsURL/StrResponse 先例）。
@Suppress("unused")
class AsymmetricCryptoAndroid(algorithm: String) : HutoolAsymmetricCrypto(algorithm), AsymmetricCrypto {

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

}
