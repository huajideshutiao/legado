package io.legado.app.help.storage

import cn.hutool.crypto.symmetric.AES
import io.legado.app.utils.MD5Utils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 以 hutool AES 为 oracle 的字节级互通向量测试：
 * BackupAES 去 hutool 后，同 key/明文密文必须逐字节一致，旧密文必须可解。
 */
class BackupAesCompatTest {

    private val keys = listOf(
        "0123456789abcdef".toByteArray(),                 // 16 字节，实际备份 key 形态
        "0123456789abcdef01234567".toByteArray(),         // 24 字节
        "0123456789abcdef0123456789abcdef".toByteArray(), // 32 字节
    )

    private val plainTexts = listOf(
        "",
        "hello legado",
        "0123456789abcdef", // 恰好一个块，验证 padding 整块追加
        "中文密码《测试》😀emoji",
        buildString { repeat(500) { append("多块长文本 webDavPassword=$it&") } },
    )

    @Test
    fun encryptBase64IdenticalToHutool() {
        for (key in keys) for (text in plainTexts) {
            assertEquals(AES(key).encryptBase64(text), BackupAES(key).encryptBase64(text))
        }
    }

    @Test
    fun oldHutoolBase64CiphertextDecryptable() {
        for (key in keys) for (text in plainTexts) {
            assertEquals(text, BackupAES(key).decryptStr(AES(key).encryptBase64(text)))
        }
    }

    @Test
    fun oldHutoolHexCiphertextDecryptable() {
        for (key in keys) for (text in plainTexts) {
            assertEquals(text, BackupAES(key).decryptStr(AES(key).encryptHex(text)))
        }
    }

    @Test
    fun hutoolCanDecryptOurCiphertext() {
        for (key in keys) for (text in plainTexts) {
            assertEquals(text, AES(key).decryptStr(BackupAES(key).encryptBase64(text)))
        }
    }

    @Test
    fun rawBytesIdentical() {
        val data = ByteArray(256) { it.toByte() }
        for (key in keys) {
            assertArrayEquals(AES(key).encrypt(data), BackupAES(key).encrypt(data))
            assertArrayEquals(data, BackupAES(key).decrypt(AES(key).encrypt(data)))
        }
    }

    @Test
    fun md5DerivedBackupKeyVector() {
        // 复刻无参构造的 key 派生：MD5(密码) 十六进制前 16 字节
        for (password in listOf("", "myPass123", "中文密码")) {
            val key = MD5Utils.md5Encode(password).encodeToByteArray(0, 16)
            assertEquals(AES(key).encryptBase64("备份内容"), BackupAES(key).encryptBase64("备份内容"))
            assertEquals("备份内容", BackupAES(key).decryptStr(AES(key).encryptBase64("备份内容")))
        }
    }
}
