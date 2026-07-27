package io.legado.app.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Base64 实现替换（android.util.Base64 → java.util.Base64）语义锁。
 * 期望值硬编码自 android.util.Base64 各 flags 的行为（oracle 向量）：
 * DEFAULT=0 标准字母表+padding+每 76 字符断行且末行也带 \n（空输入除外）；
 * NO_PADDING=1；NO_WRAP=2；CRLF=4 换行符用 \r\n；URL_SAFE=8 用 -_ 字母表；
 * 解码一律跳过字母表外字符（含换行）。
 */
class EncoderUtilsTest {

    @Test
    fun `escape 不受迁移影响`() {
        assertEquals("abc123", EncoderUtils.escape("abc123"))
        assertEquals("%20%2b", EncoderUtils.escape(" +"))
        assertEquals("%u4e2d", EncoderUtils.escape("中"))
        assertEquals("%0a", EncoderUtils.escape("\n"))
    }

    @Test
    fun `encode DEFAULT 短输入 单行加尾换行`() {
        // android.util.Base64.encodeToString("Hello, World!".getBytes(), DEFAULT)
        assertEquals("SGVsbG8sIFdvcmxkIQ==\n", EncoderUtils.base64Encode("Hello, World!", 0))
    }

    @Test
    fun `encode DEFAULT 空输入无换行`() {
        // android 对空输入返回空串, 不追加换行
        assertEquals("", EncoderUtils.base64Encode(ByteArray(0), 0))
    }

    @Test
    fun `encode DEFAULT 超 76 字符断行 末行也带换行`() {
        // 60 字节 → 80 字符: android 在 76 字符处断行, 每行(含末行)以 \n 结尾
        val expected = "YWFh".repeat(19) + "\n" + "YWFh" + "\n"
        assertEquals(expected, EncoderUtils.base64Encode("a".repeat(60), 0))
    }

    @Test
    fun `encode DEFAULT 恰 76 字符只一个换行`() {
        // 57 字节 → 恰 76 字符: android 断行后 finish 分支不再补换行
        assertEquals("YWFh".repeat(19) + "\n", EncoderUtils.base64Encode("a".repeat(57), 0))
    }

    @Test
    fun `encode CRLF 换行符为 rn`() {
        assertEquals("SGVsbG8sIFdvcmxkIQ==\r\n", EncoderUtils.base64Encode("Hello, World!", 4))
        val expected = "YWFh".repeat(19) + "\r\n" + "YWFh" + "\r\n"
        assertEquals(expected, EncoderUtils.base64Encode("a".repeat(60), 4))
    }

    @Test
    fun `encode NO_WRAP 标准字母表加 padding 不换行`() {
        assertEquals("SGVsbG8sIFdvcmxkIQ==", EncoderUtils.base64Encode("Hello, World!", 2))
        // 默认 flags 即 NO_WRAP
        assertEquals("SGVsbG8sIFdvcmxkIQ==", EncoderUtils.base64Encode("Hello, World!"))
        // 超 76 字符也不断行
        assertEquals("YWFh".repeat(20), EncoderUtils.base64Encode("a".repeat(60), 2))
    }

    @Test
    fun `encode NO_PADDING 组合去 padding`() {
        // NO_WRAP or NO_PADDING = 3
        assertEquals("SGVsbG8sIFdvcmxkIQ", EncoderUtils.base64Encode("Hello, World!", 3))
        // NO_PADDING 单用 = 1: 去 padding 但保留尾换行
        assertEquals("SGVsbG8sIFdvcmxkIQ\n", EncoderUtils.base64Encode("Hello, World!", 1))
    }

    @Test
    fun `encode URL_SAFE 用减号下划线字母表`() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xef.toByte(), 0xff.toByte())
        // 标准字母表: ++//
        assertEquals("++//", EncoderUtils.base64Encode(bytes, 2))
        // URL_SAFE or NO_WRAP = 10: --__
        assertEquals("--__", EncoderUtils.base64Encode(bytes, 10))
        // URL_SAFE 带 padding: 0xfb → -w==
        assertEquals("-w==", EncoderUtils.base64Encode(byteArrayOf(0xfb.toByte()), 10))
    }

    @Test
    fun `decode DEFAULT 标准输入`() {
        assertEquals("Hello, World!", EncoderUtils.base64Decode("SGVsbG8sIFdvcmxkIQ==", 0))
        assertArrayEquals(
            "Hello, World!".toByteArray(),
            EncoderUtils.base64DecodeToByteArray("SGVsbG8sIFdvcmxkIQ==", 0)
        )
    }

    @Test
    fun `decode DEFAULT 跳过换行等字母表外字符`() {
        // android 解码表把非字母表字符标 SKIP, 混入换行/空格不报错
        assertEquals("Hello, World!", EncoderUtils.base64Decode("SGVs\nbG8s\r\nIFdv cmxkIQ==\n", 0))
    }

    @Test
    fun `decode DEFAULT 无 padding 输入可解`() {
        assertEquals("Hello, World!", EncoderUtils.base64Decode("SGVsbG8sIFdvcmxkIQ", 0))
    }

    @Test
    fun `decode URL_SAFE 字母表及跳过换行`() {
        val expected = byteArrayOf(0xfb.toByte(), 0xef.toByte(), 0xff.toByte())
        assertArrayEquals(expected, EncoderUtils.base64DecodeToByteArray("--__", 8))
        assertArrayEquals(expected, EncoderUtils.base64DecodeToByteArray("--\n__", 8))
        assertArrayEquals(byteArrayOf(0xfb.toByte()), EncoderUtils.base64DecodeToByteArray("-w==", 8))
    }
}
