package io.legado.app.help.storage

/**
 * 备份加解密：AES/ECB/PKCS5Padding，key 为 MD5(备份密码) 十六进制前 16 字节。
 *
 * KMP expect class: 各端 actual 实现需保证字节级互通。
 *
 * - jvmAndAndroidMain actual: javax.crypto.Cipher + java.util.Base64 (原实现, 行为零变化)
 * - iosMain actual: 纯 Kotlin AES 实现 (无平台依赖, 字节级对齐 javax.crypto 输出)
 * - ohosMain actual: 纯 Kotlin AES 实现 (同 iosMain, 保证鸿蒙端备份加解密可用)
 *
 * 字节级互通保证:
 * - 算法: AES/ECB/PKCS5Padding (PKCS#5 = PKCS#7 块大小 16)
 * - 密文形态: encryptBase64 输出标准 Base64 (带 padding, 不换行)
 * - decryptStr 兼容 hex 与 base64 两种密文形态 (对齐 hutool SecureUtil.decode 自动识别)
 *
 * 向量守护: app/src/test/java/io/legado/app/help/storage/BackupAesCompatTest.kt
 * 覆盖 16/24/32 字节 key × 多种明文 (含中文/emoji/多块长文本) × hutool AES oracle 互通。
 */
expect class BackupAES(key: ByteArray) {

    constructor()

    fun encrypt(data: ByteArray): ByteArray

    fun encryptBase64(data: String): String

    fun decrypt(data: ByteArray): ByteArray

    /** 密文兼容 hex 与 base64 两种形态，对齐 hutool SecureUtil.decode 的自动识别 */
    fun decryptStr(data: String): String
}
