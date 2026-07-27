package io.legado.app.help.storage

import io.legado.app.help.config.PasswordProviders
import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.MD5Utils
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 备份加解密：AES/ECB/PKCS5Padding，key 为 MD5(备份密码) 十六进制前 16 字节。
 * 与旧版 hutool AES(key) 默认参数字节级互通，旧备份可解，有向量测试守护。
 *
 * 下沉 shared jvmAndAndroidMain 后, 原 app 端 `LocalConfig.password` (SharedPreferences)
 * 经 [PasswordProviders] 接口注入, 行为不变 (未注册时 password() 返回 null, 等价默认空密码)。
 *
 * javax.crypto.Cipher / SecretKeySpec / java.util.Base64 均为 JVM API,
 * 故下沉至 jvmAndAndroidMain (android + jvm 共用), 而非 commonMain。
 *
 * KMP 化: 加 `actual class` 标记, 对齐 commonMain expect class BackupAES。
 * 方法体零变化: 仅追加 `actual` 修饰符, 内部逻辑不动 (BackupAesCompatTest 向量守护)。
 */
actual class BackupAES actual constructor(key: ByteArray) {

    actual constructor() : this(
        MD5Utils.md5Encode(PasswordProviders.get()?.password() ?: "").encodeToByteArray(0, 16)
    )

    private val secretKey = SecretKeySpec(key, "AES")

    private fun cipher(mode: Int): Cipher =
        Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(mode, secretKey) }

    actual fun encrypt(data: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE).doFinal(data)

    actual fun encryptBase64(data: String): String =
        Base64.getEncoder().encodeToString(encrypt(data.encodeToByteArray()))

    actual fun decrypt(data: ByteArray): ByteArray = cipher(Cipher.DECRYPT_MODE).doFinal(data)

    /** 密文兼容 hex 与 base64 两种形态，对齐 hutool SecureUtil.decode 的自动识别 */
    actual fun decryptStr(data: String): String {
        val bytes = if (hexRegex.matches(data)) decodeHex(data) else Base64Lenient.decode(data)
        return String(decrypt(bytes), Charsets.UTF_8)
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
