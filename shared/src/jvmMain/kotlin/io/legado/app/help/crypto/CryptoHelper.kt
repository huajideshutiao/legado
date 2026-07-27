package io.legado.app.help.crypto

import io.legado.app.utils.Base64Lenient
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * JVM actual: 与 Android actual 完全一致 (javax.crypto + java.util.Base64 均为 JDK 标准)。
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
