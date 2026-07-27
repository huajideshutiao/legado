package io.legado.app.help.storage

import com.soywiz.krypto.AES
import io.legado.app.help.config.PasswordProviders
import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.encodeBase64Standard

/**
 * nativeMain actual: 备份加解密 AES/ECB/PKCS5Padding, 基于 krypto 库实现。
 *
 * 由 iosMain / ohosMain 共用 (nativeMain 中间源集下沉, 两端 actual 完全一致)。
 *
 * - 算法与 jvmAndAndroidMain 的 javax.crypto.Cipher("AES/ECB/PKCS5Padding") 字节级互通,
 *   旧备份 (jvmAndAndroid 生成) 可在 iOS/鸿蒙端解密, 反之亦然。
 * - krypto [AES.Padding.PKCS7Padding] 在 AES 块大小 (16 字节) 下与 PKCS5Padding 等价。
 * - encryptBase64 输出标准 Base64 (带 padding, 不换行), 对齐 java.util.Base64.getEncoder()。
 * - decryptStr 兼容 hex 与 base64 两种密文形态, 对齐 hutool SecureUtil.decode 自动识别。
 *
 * 注: krypto 4.0.10 已发布 linuxArm64 变体, 鸿蒙端走 linuxArm64 target, iOS 端走 iosArm64/
 * iosX64/iosSimulatorArm64 target, 均可直接复用本实现。
 *
 * 向量守护: app/src/test/java/io/legado/app/help/storage/BackupAesCompatTest.kt
 * （jvmAndAndroid 端 oracle 测试, 覆盖 16/24/32 字节 key × 多种明文 × hutool AES 互通）。
 */
actual class BackupAES actual constructor(key: ByteArray) {

    actual constructor() : this(
        MD5Utils.md5Encode(PasswordProviders.get()?.password() ?: "").encodeToByteArray(0, 16)
    )

    private val aes = AES(key, mode = AES.Mode.ECB, padding = AES.Padding.PKCS7Padding)

    actual fun encrypt(data: ByteArray): ByteArray = aes.encrypt(data)

    actual fun encryptBase64(data: String): String =
        aes.encrypt(data.encodeToByteArray()).encodeBase64Standard()

    actual fun decrypt(data: ByteArray): ByteArray = aes.decrypt(data)

    /** 密文兼容 hex 与 base64 两种形态, 对齐 hutool SecureUtil.decode 的自动识别 */
    actual fun decryptStr(data: String): String {
        val bytes = if (hexRegex.matches(data)) decodeHex(data) else Base64Lenient.decode(data)
        return aes.decrypt(bytes).decodeToString()
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
