package io.legado.app.help.crypto

import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.encodeBase64Standard

/**
 * nativeMain actual: AES/ECB/PKCS5Padding + Base64 (iOS / 鸿蒙 两端共用壳)。
 *
 * 真实加解密下沉到 [NativeAesOps] (expect object): 两端 actual 均 mbedTLS 主实现, 异常回落 krypto/napi。
 *
 * - PKCS7Padding 在 AES 块大小 (16 字节) 下与 PKCS5Padding 等价
 *   （PKCS#5 是 PKCS#7 在块大小 8 时的特例；AES 块固定 16 字节, 二者填充字节序列完全一致）。
 * - 密文形态与 jvmAndAndroidMain 的 javax.crypto.Cipher("AES/ECB/PKCS5Padding") 字节级一致。
 * - Base64 解码走 [Base64Lenient]（容错, 对齐 jvmAndAndroid 行为）；
 *   Base64 编码走 [encodeBase64Standard]（标准字母表 + padding + 不换行,
 *   对齐 java.util.Base64.getEncoder()）。
 *
 * 影响范围: BaseSource.getLoginInfo/putLoginInfo 的书源登录信息加密存储 (iOS/鸿蒙端可用)。
 */
actual object CryptoHelper {

    actual fun decryptAesEcbPkcs5Base64(key: ByteArray, base64Data: String): String {
        return NativeAesOps.decryptEcbPkcs7(key, Base64Lenient.decode(base64Data)).decodeToString()
    }

    actual fun encryptAesEcbPkcs5Base64(key: ByteArray, data: String): String {
        return NativeAesOps.encryptEcbPkcs7(key, data.encodeToByteArray()).encodeBase64Standard()
    }
}
