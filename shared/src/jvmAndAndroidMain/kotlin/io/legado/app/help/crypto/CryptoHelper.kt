package io.legado.app.help.crypto

import io.legado.app.utils.Base64Lenient
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * jvmAndAndroid actual: AES/ECB/PKCS5Padding + java.util.Base64。
 *
 * 复刻原 BaseSource.getLoginInfo/putLoginInfo inline 加解密路径, 行为零变化
 * (javax.crypto + java.util.Base64 均为 JVM/Android 标准):
 * - 解密: Base64Lenient.decode (容错) → Cipher DECRYPT → UTF-8 String
 * - 加密: UTF-8 bytes → Cipher ENCRYPT → Base64.getEncoder().encodeToString
 */
actual object CryptoHelper {

    actual fun decryptAesEcbPkcs5Base64(key: ByteArray, base64Data: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return String(cipher.doFinal(Base64Lenient.decode(base64Data)), Charsets.UTF_8)
    }

    actual fun encryptAesEcbPkcs5Base64(key: ByteArray, data: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return Base64.getEncoder()
            .encodeToString(cipher.doFinal(data.toByteArray(Charsets.UTF_8)))
    }
}
