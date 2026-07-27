package io.legado.app.utils

import io.legado.app.lib.icu4j.CharsetDetector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class EncodingDetectTest {

    private val sampleText = "阅读是一款自由且开源的网络文学阅读器，功能强大，支持自定义书源。" +
            "本段文字用于字符集探测的确定性断言，长度需足够以获得稳定置信度。"

    @Test
    fun detectUtf8Bytes() {
        val bytes = sampleText.toByteArray(Charsets.UTF_8)
        val match = CharsetDetector().setText(bytes).detect()
        assertEquals("UTF-8", match?.name)
    }

    @Test
    fun detectGbkBytes() {
        val bytes = sampleText.toByteArray(Charset.forName("GBK"))
        val match = CharsetDetector().setText(bytes).detect()
        // GBK 是 GB18030 的子集，探测器统一报 GB18030
        assertEquals("GB18030", match?.name)
    }

    @Test
    fun removeUtf8Bom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val payload = "hello".toByteArray()
        val withBom = bom + payload
        assertArrayEquals(payload, Utf8BomUtils.removeUTF8BOM(withBom))
        // 无 BOM 时原样返回
        assertArrayEquals(payload, Utf8BomUtils.removeUTF8BOM(payload))
    }
}
