package io.legado.app.help.crypto

import cn.hutool.core.codec.Base64
import cn.hutool.core.util.HexUtil
import cn.hutool.crypto.symmetric.SymmetricCrypto
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.isHex
import java.io.InputStream
import java.nio.charset.Charset
import javax.crypto.spec.SecretKeySpec

// KMP 化: 追加 `: SymmetricCrypto` (commonMain interface) 实现标记。
// class 名 `SymmetricCryptoAndroid` 与 commonMain interface `SymmetricCrypto` 名字不同, 不冲突。
// 方法体零变化: 原 override 标记 (override hutool 父类) 同时承担 interface 方法实现, 无需新增 override。
// JVM-only 附加成员 (encryptBase64(InputStream) / encryptBase64(String, Charset?)) 保留,
// 不暴露到 commonMain interface, 避免 commonMain 引用 java.io.InputStream / java.nio.charset.Charset。
//
// @Keep 移除：shared 无 androidx.annotation 依赖，JS 桥反射保活改由 consumer-rules.pro -keep 登记（照 JsURL/StrResponse 先例）。
//
// key 构造说明: 不能用 hutool SymmetricCrypto(algorithm, key: ByteArray?) 构造器——
// 其内部 KeyUtil.generateKey 把完整 transformation ("AES/ECB/PKCS5Padding") 原样传给
// SecretKeySpec 作算法名, Android BC/Conscrypt 不校验, 但桌面端 JVM SunJCE 严格校验
// (InvalidKeyException: Wrong algorithm: AES or Rijndael required)。
// 改用 (algorithm, SecretKey) 构造器: key 算法名取 transformation 的 '/' 前主算法,
// Cipher 实例化仍用完整 transformation, 三端行为与 Android 一致。
class SymmetricCryptoAndroid(
    algorithm: String,
    key: ByteArray?,
) : SymmetricCrypto(
    // PKCS7Padding → PKCS5Padding 归一: 两者在块密码 (AES 16 字节/DES 8 字节) 下字节级等价,
    // hutool 内部 Cipher.getInstance("AES/CBC/PKCS7Padding") 在无该 provider 的平台
    // (桌面端 SunJCE / 个别 Android ROM) 会抛 NoSuchAlgorithmException; 归一后全平台可用,
    // 密文与真 PKCS7 逐字节一致。
    //
    // 2026-08-15 教训: 不要为了真 PKCS7 引入 bcprov (见 desktop/build.gradle.kts 注释)——
    // BC 类在 classpath 会让 hutool 的 RSA Cipher 走 BC (getBlockSize=127 触发分段加密),
    // 网易云 weapi encSecKey 错误全站 200 空体。归一化是唯一需要的 PKCS7 方案。
    algorithm.normalizePkcs7Padding(),
    key?.let { SecretKeySpec(it, algorithm.substringBefore('/')) }
), io.legado.app.help.crypto.SymmetricCrypto {

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

/**
 * 把 transformation 里的 PKCS7Padding 归一为 PKCS5Padding (大小写不敏感)。
 *
 * 依据: JCE 的 "PKCS5Padding" 实现按 cipher 实际块大小填充 (AES 16 字节/DES 8 字节),
 * 与 PKCS7Padding (RFC 5652) 的填充字节逐字节一致; 对 hutool 支持的块密码 (AES/DES/
 * DESede/SM4) 两者可互换, 不影响密文与解密兼容性。仅 Android/iOS/ohos 的 mbedTLS
 * 原生后端无需此归一 (原生实现直接支持 PKCS7Padding)。
 */
internal fun String.normalizePkcs7Padding(): String =
    if (contains("PKCS7Padding", ignoreCase = true)) {
        Regex("(?i)PKCS7Padding").replace(this, "PKCS5Padding")
    } else {
        this
    }
