package io.legado.app.help.crypto

/**
 * BaseSource 下沉 commonMain 的 AES/Base64 加解密桥接。
 *
 * commonMain 不允许直接引用 javax.crypto.Cipher / java.util.Base64; 经 expect/actual
 * 把 BaseSource.getLoginInfo/putLoginInfo 的加解密调用下沉到各端 actual 实现,
 * 行为与原 inline 实现完全一致 (AES/ECB/PKCS5Padding + Base64)。
 *
 * - decryptAesEcbPkcs5Base64: 先 Base64Lenient.decode (容错) 再 AES 解密, 返回 UTF-8 字符串
 * - encryptAesEcbPkcs5Base64: AES 加密后 Base64 编码 (NO_WRAP, 对齐 java.util.Base64.getEncoder())
 */
expect object CryptoHelper {

    /**
     * AES/ECB/PKCS5Padding 解密 + Base64 解码 (容错: 走 Base64Lenient)。
     *
     * @param key   AES 密钥 (16 字节)
     * @param base64Data Base64 编码的密文
     * @return UTF-8 字符串
     */
    fun decryptAesEcbPkcs5Base64(key: ByteArray, base64Data: String): String

    /**
     * AES/ECB/PKCS5Padding 加密 + Base64 编码 (标准字母表 + padding, 对齐 java.util.Base64.getEncoder())。
     *
     * @param key  AES 密钥 (16 字节)
     * @param data UTF-8 字符串
     * @return Base64 编码的密文
     */
    fun encryptAesEcbPkcs5Base64(key: ByteArray, data: String): String
}
