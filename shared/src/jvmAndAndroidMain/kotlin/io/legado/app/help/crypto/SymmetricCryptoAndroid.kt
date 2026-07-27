package io.legado.app.help.crypto

import cn.hutool.core.codec.Base64
import cn.hutool.core.util.HexUtil
import cn.hutool.crypto.symmetric.SymmetricCrypto
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.isHex
import java.io.InputStream
import java.nio.charset.Charset

// KMP 化: 追加 `: SymmetricCrypto` (commonMain interface) 实现标记。
// class 名 `SymmetricCryptoAndroid` 与 commonMain interface `SymmetricCrypto` 名字不同, 不冲突。
// 方法体零变化: 原 override 标记 (override hutool 父类) 同时承担 interface 方法实现, 无需新增 override。
// JVM-only 附加成员 (encryptBase64(InputStream) / encryptBase64(String, Charset?)) 保留,
// 不暴露到 commonMain interface, 避免 commonMain 引用 java.io.InputStream / java.nio.charset.Charset。
//
// @Keep 移除：shared 无 androidx.annotation 依赖，JS 桥反射保活改由 consumer-rules.pro -keep 登记（照 JsURL/StrResponse 先例）。
class SymmetricCryptoAndroid(
    algorithm: String,
    key: ByteArray?,
) : SymmetricCrypto(algorithm, key), io.legado.app.help.crypto.SymmetricCrypto {

    // 新接口方法 encrypt/encryptHex: hutool 父类已有同签名实现 (encrypt(byte[]) 具体方法,
    // 其余 SymmetricEncryptor default 方法), 一行 super 直通仅作 override 标记。
    override fun encrypt(data: ByteArray): ByteArray = super.encrypt(data)

    override fun encrypt(data: String): ByteArray = super.encrypt(data)

    override fun encryptHex(data: ByteArray): String = super.encryptHex(data)

    override fun encryptHex(data: String): String = super.encryptHex(data)

    override fun encryptBase64(data: ByteArray): String {
        return EncoderUtils.base64Encode(encrypt(data))
    }

    override fun encryptBase64(data: String, charset: String?): String {
        return EncoderUtils.base64Encode(encrypt(data, charset))
    }

    override fun encryptBase64(data: String, charset: Charset?): String {
        return EncoderUtils.base64Encode(encrypt(data, charset))
    }

    override fun encryptBase64(data: String): String {
        return EncoderUtils.base64Encode(encrypt(data))
    }

    override fun encryptBase64(data: InputStream): String {
        return EncoderUtils.base64Encode(encrypt(data))
    }

    override fun decrypt(data: String): ByteArray {
        val bytes = if (data.isHex()) {
            HexUtil.decodeHex(data)
        } else {
            Base64.decode(data)
        }
        return decrypt(bytes)
    }

}
