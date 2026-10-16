package io.legado.app.help.compress

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.JsExtensionsCommon
import io.legado.app.model.fileBook.ZipTestFixtures.deflateRaw
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 内存单段解压 (gzip / zlib / 裸 deflate) 与 auto 嗅探, 以及 JsExtensions 侧三态入参
 * (字节数组 / 十六进制字符串 / 列表) 与字符串输出。
 */
class StreamDecompressorTest {

    /** 每个样本: format 名 + 明文 + 该格式的压缩字节。 */
    private class Sample(val format: String, val plain: String, val packed: ByteArray)

    private val plain = "内存解压样本 payload 0123456789 ".repeat(200)
    private val plainBytes = plain.encodeToByteArray()

    private val samples = listOf(
        Sample("gzip", plain, gzip(plainBytes)),
        Sample("zlib", plain, zlib(plainBytes)),
        Sample("deflate", plain, deflateRaw(plainBytes)),
    )

    private val ext = object : JsExtensionsCommon {
        override fun getSource(): BaseSource? = null
    }

    // --- 三格式解压 ---

    @Test
    fun decompressEachFormat() {
        samples.forEach { sample ->
            assertArrayEquals(
                sample.format,
                plainBytes,
                StreamDecompressor.decompress(sample.packed, sample.format)
            )
        }
    }

    @Test
    fun autoDetectsEachFormat() {
        samples.forEach { sample ->
            assertArrayEquals(
                sample.format,
                plainBytes,
                StreamDecompressor.decompress(sample.packed, "auto")
            )
        }
    }

    @Test
    fun formatNameIsCaseAndSpaceInsensitive() {
        assertArrayEquals(plainBytes, StreamDecompressor.decompress(samples[0].packed, " GZIP "))
    }

    // --- 失败口径: 返回 null ---

    @Test
    fun damagedOrMismatchedDataReturnsNull() {
        samples.forEach { sample ->
            // 未压缩的明文当压缩数据
            assertNull("${sample.format} 明文", StreamDecompressor.decompress(plainBytes, sample.format))
            // 截断一半
            assertNull(
                "${sample.format} 截断",
                StreamDecompressor.decompress(
                    sample.packed.copyOf(sample.packed.size / 2),
                    sample.format
                )
            )
            // 空输入
            assertNull(
                "${sample.format} 空输入",
                StreamDecompressor.decompress(ByteArray(0), sample.format)
            )
        }
    }

    @Test
    fun autoOnUncompressedDataReturnsNull() {
        assertNull(StreamDecompressor.decompress(plainBytes, "auto"))
    }

    @Test
    fun unknownFormatThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamDecompressor.decompress(samples[0].packed, "zstd")
        }
    }

    // --- JsExtensions 侧: 三态入参 + 字符串输出 ---

    @Test
    fun bytesHexAndListInputsAgree() {
        samples.forEach { sample ->
            assertArrayEquals(sample.format, plainBytes, ext.decompressBytes(sample.packed, sample.format))
            assertArrayEquals(
                sample.format,
                plainBytes,
                ext.decompressBytes(sample.packed.toHexString(), sample.format)
            )
            assertArrayEquals(
                sample.format,
                plainBytes,
                ext.decompressBytes(sample.packed.map { it.toInt() }, sample.format)
            )
        }
    }

    @Test
    fun decompressStringDecodesByCharset() {
        // 指定编码: 中文明文按 UTF-8 解出原文
        samples.forEach { sample ->
            assertEquals(plain, ext.decompressString(sample.packed, sample.format, "UTF-8"))
        }
        // 自动检测编码: ASCII 明文下与显式编码结果一致
        val asciiText = "memory decompress sample 0123456789 ".repeat(200)
        assertEquals(asciiText, ext.decompressString(gzip(asciiText.encodeToByteArray()), "gzip"))
    }

    @Test
    fun nullAndInvalidData() {
        assertNull(ext.decompressBytes(null, "gzip"))
        assertEquals("", ext.decompressString(null, "gzip"))
        assertEquals("", ext.decompressString(plainBytes, "gzip"))
        assertThrows(IllegalArgumentException::class.java) { ext.decompressBytes(1, "gzip") }
        assertThrows(IllegalArgumentException::class.java) { ext.decompressBytes(plainBytes, "zstd") }
    }

    private companion object {

        fun gzip(data: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(data) }
            return bos.toByteArray()
        }

        /** zlib 包裹 (nowrap=false, 带 zlib 头与 adler32) */
        fun zlib(data: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream()
            DeflaterOutputStream(bos, Deflater(Deflater.DEFAULT_COMPRESSION, false))
                .use { it.write(data) }
            return bos.toByteArray()
        }
    }
}
