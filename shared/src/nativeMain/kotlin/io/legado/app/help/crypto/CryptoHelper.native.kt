package io.legado.app.help.crypto

import com.soywiz.krypto.AES
import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.encodeBase64Standard

/**
 * nativeMain actual: AES/ECB/PKCS5Padding + Base64, 基于 krypto 库实现 (iOS / 鸿蒙 两端共用)。
 *
 * 两端 actual object 实现完全一致, 下沉到 nativeMain 共用 (nativeMain 是 iosMain / ohosMain
 * 的父源集, 此处提供 actual 自动满足两端)。
 *
 * - krypto 的 [AES.Padding.PKCS7Padding] 在 AES 块大小 (16 字节) 下与 PKCS5Padding 等价
 *   （PKCS#5 是 PKCS#7 在块大小 8 时的特例；AES 块固定 16 字节, 二者填充字节序列完全一致）。
 * - 密文形态与 jvmAndAndroidMain 的 javax.crypto.Cipher("AES/ECB/PKCS5Padding") 字节级一致。
 * - Base64 解码走 [Base64Lenient]（容错, 对齐 jvmAndAndroid 行为）；
 *   Base64 编码走 [encodeBase64Standard]（标准字母表 + padding + 不换行,
 *   对齐 java.util.Base64.getEncoder()）。
 *
 * 影响范围: BaseSource.getLoginInfo/putLoginInfo 的书源登录信息加密存储 (iOS/鸿蒙端可用)。
 *
 * 注: krypto 4.0.10 已发布 linuxArm64 变体, 鸿蒙端走 linuxArm64 target, 可直接复用 iOS 端实现,
 * 故两端下沉到 nativeMain 共用。
 */
actual object CryptoHelper {

    private fun aesCipher(key: ByteArray): AES =
        AES(key, mode = AES.Mode.ECB, padding = AES.Padding.PKCS7Padding)

    actual fun decryptAesEcbPkcs5Base64(key: ByteArray, base64Data: String): String {
        val cipher = aesCipher(key)
        return cipher.decrypt(Base64Lenient.decode(base64Data)).decodeToString()
    }

    actual fun encryptAesEcbPkcs5Base64(key: ByteArray, data: String): String {
        val cipher = aesCipher(key)
        return cipher.encrypt(data.encodeToByteArray()).encodeBase64Standard()
    }
}
