package io.legado.app.help.storage

import io.legado.app.help.config.PasswordProviders
import io.legado.app.help.crypto.NativeAesOps
import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.encodeBase64Standard

/**
 * nativeMain actual: 备份加解密 AES/ECB/PKCS5Padding (iOS / 鸿蒙 两端共用壳)。
 *
 * 真实加解密下沉到 [NativeAesOps] (expect object): iOS 端 krypto actual, 鸿蒙端 napi actual。
 *
 * - 算法与 jvmAndAndroidMain 的 javax.crypto.Cipher("AES/ECB/PKCS5Padding") 字节级互通,
 *   旧备份 (jvmAndAndroid 生成) 可在 iOS/鸿蒙端解密, 反之亦然。
 * - PKCS7Padding 在 AES 块大小 (16 字节) 下与 PKCS5Padding 等价。
 * - encryptBase64 输出标准 Base64 (带 padding, 不换行), 对齐 java.util.Base64.getEncoder()。
 * - decryptStr 兼容 hex 与 base64 两种密文形态, 对齐 hutool SecureUtil.decode 自动识别。
 *
 * 向量守护: app/src/test/java/io/legado/app/help/storage/BackupAesCompatTest.kt
 * （jvmAndAndroid 端 oracle 测试, 覆盖 16/24/32 字节 key × 多种明文 × hutool AES 互通）。
 */
actual class BackupAES actual constructor(key: ByteArray) {

    actual constructor() : this(
        MD5Utils.md5Encode(PasswordProviders.get()?.password() ?: "").encodeToByteArray(0, 16)
    )

    private val aesKey = key

    actual fun encrypt(data: ByteArray): ByteArray = NativeAesOps.encryptEcbPkcs7(aesKey, data)

    actual fun encryptBase64(data: String): String =
        NativeAesOps.encryptEcbPkcs7(aesKey, data.encodeToByteArray()).encodeBase64Standard()

    actual fun decrypt(data: ByteArray): ByteArray = NativeAesOps.decryptEcbPkcs7(aesKey, data)

    /** 密文兼容 hex 与 base64 两种形态, 对齐 hutool SecureUtil.decode 的自动识别 */
    actual fun decryptStr(data: String): String {
        val bytes = if (hexRegex.matches(data)) decodeHex(data) else Base64Lenient.decode(data)
        return NativeAesOps.decryptEcbPkcs7(aesKey, bytes).decodeToString()
    }

    private fun decodeHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "invalid hex length: ${hex.length}" }
        return ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private val hexRegex = Regex("^[a-fA-F0-9]+$")
    }
}
