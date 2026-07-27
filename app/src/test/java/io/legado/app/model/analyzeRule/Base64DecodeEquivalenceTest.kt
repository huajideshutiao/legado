package io.legado.app.model.analyzeRule

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * 守护 K3 重构: data URI 的 base64 解码从 android.util.Base64.decode(s, DEFAULT)
 * 换成 java.util.Base64.getMimeDecoder().decode(s)。
 *
 * android 的 Base64.DEFAULT 解码用标准字母表, 会跳过空白/换行, 处理 padding;
 * java 标准 getDecoder() 遇空白会抛异常, 只有 getMimeDecoder() 与之等价。
 * 书源里 dataURL 常带换行, 解码结果错一个字节就破图/破书源, 故此处逐向量核对。
 */
class Base64DecodeEquivalenceTest {

    private fun decode(s: String): ByteArray = Base64.getMimeDecoder().decode(s)

    @Test
    fun `basic ascii round trip`() {
        // "Man" -> TWFu (RFC4648 经典向量)
        assertArrayEquals("Man".toByteArray(), decode("TWFu"))
        assertEquals("Man", String(decode("TWFu")))
    }

    @Test
    fun `padding variants`() {
        assertEquals("M", String(decode("TQ==")))
        assertEquals("Ma", String(decode("TWE=")))
        assertEquals("Man", String(decode("TWFu")))
    }

    @Test
    fun `crlf line wrapping is skipped like android DEFAULT`() {
        // 书源 dataURL 大图常按 MIME 每 76 字符换行; android DEFAULT 与 MIME decoder 均忽略换行
        val expected = "Hello, 世界".toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encodeToString(expected)
        assertArrayEquals(expected, decode(encoded.chunked(4).joinToString("\r\n")))
        assertArrayEquals(expected, decode(encoded.chunked(4).joinToString("\n")))
    }

    @Test
    fun `leading trailing and embedded whitespace is skipped`() {
        // 真实 dataURL 里的空白只会出现在数据段之间(换行/空格), 不会切开尾部 padding
        val expected = "Hello, 世界".toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encodeToString(expected)
        assertArrayEquals(expected, decode("  \n$encoded\n  "))
        // 在非 padding 位置插空格
        val withGaps = encoded.dropLast(2).chunked(2).joinToString(" ") + encoded.takeLast(2)
        assertArrayEquals(expected, decode(withGaps))
    }

    @Test
    fun `binary bytes exact match`() {
        val expected = byteArrayOf(0x00, 0x0F, 0x10, 0x7F, -0x80, -0x01, 0x2A)
        val encoded = Base64.getEncoder().encodeToString(expected)
        assertArrayEquals(expected, decode(encoded))
    }

    @Test
    fun `full range round trip`() {
        val expected = ByteArray(256) { it.toByte() }
        val encoded = Base64.getEncoder().encodeToString(expected)
        assertArrayEquals(expected, decode(encoded))
    }
}
