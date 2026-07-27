package io.legado.app.model.fileBook

import io.legado.app.model.fileBook.ZipTestFixtures.Spec
import io.legado.app.model.fileBook.ZipTestFixtures.buildZip
import io.legado.app.model.fileBook.ZipTestFixtures.deflateRaw
import io.legado.app.model.fileBook.ZipTestFixtures.sourceOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** DEFLATE 解压原语 inflateRaw + RemoteZipCore.readDecompressed 结构→解压闭环 */
class InflateTest {

    // --- inflateRaw 原语向量 ---

    @Test
    fun inflateRawRoundTripsText() {
        val src = "远程zip整链路 deflate vector ".repeat(300).toByteArray()
        val compressed = deflateRaw(src)
        assertArrayEquals(src, inflateRaw(compressed, 0, compressed.size, src.size))
    }

    @Test
    fun inflateRawRoundTripsBinary() {
        val src = ByteArray(20000) { (it * 31 + 7 and 0xff).toByte() }
        val compressed = deflateRaw(src)
        assertArrayEquals(src, inflateRaw(compressed, 0, compressed.size, src.size))
    }

    @Test
    fun inflateRawHandlesEmptyPayload() {
        val compressed = deflateRaw(ByteArray(0))
        assertArrayEquals(ByteArray(0), inflateRaw(compressed, 0, compressed.size, 0))
    }

    @Test
    fun inflateRawWithUnknownExpectedSize() {
        val src = ByteArray(4096) { (it % 251).toByte() }
        val compressed = deflateRaw(src)
        // expectedSize<0：仍能出全内容（内部按需扩容）
        assertArrayEquals(src, inflateRaw(compressed, 0, compressed.size, -1))
    }

    @Test
    fun inflateRawRespectsOffsetAndLength() {
        val src = "offset slice payload ".repeat(50).toByteArray()
        val compressed = deflateRaw(src)
        // 前后加噪声，验证 offset/length 精确切片
        val padded = ByteArray(compressed.size + 12)
        compressed.copyInto(padded, 5)
        assertArrayEquals(src, inflateRaw(padded, 5, compressed.size, src.size))
    }

    // --- readDecompressed 闭环：真实 zip 条目字节 ---

    private val deflated = "chapter body ".repeat(400).toByteArray()       // 高冗余→method 8
    private val stored = ByteArray(3000) { (it * 7 % 251).toByte() }        // stored
    private val specs = listOf(
        Spec("text/deflated.txt", deflated),
        Spec("img/stored.jpg", stored, stored = true),
    )
    private val zip = buildZip(specs)

    private fun core() = RemoteZipCore(sourceOf(zip), "vec.zip", zip.size.toLong())

    @Test
    fun readDecompressedDeflateEntry() {
        val c = core()
        val m = c.ensureMeta().getValue("text/deflated.txt")
        assertEquals(8, m.entry.method)   // 确认 java 选了 DEFLATE
        assertArrayEquals(deflated, c.readDecompressed(m))
    }

    @Test
    fun readDecompressedStoredEntry() {
        val c = core()
        val m = c.ensureMeta().getValue("img/stored.jpg")
        assertEquals(0, m.entry.method)   // STORED
        assertArrayEquals(stored, c.readDecompressed(m))
    }

    @Test
    fun readDecompressedStoredMatchesRawCompressedSlice() {
        // stored 分支须精确按 chunk.offset/length 切片，不能带 LOC 尾巴
        val c = core()
        val m = c.ensureMeta().getValue("img/stored.jpg")
        val out = c.readDecompressed(m)
        assertEquals(stored.size, out.size)
        assertArrayEquals(stored, out)
    }

    @Test
    fun readDecompressedSecondCallConsistent() {
        // 首读缓存 dataOffset 后，二读走缓存路径仍一致
        val c = core()
        val m = c.ensureMeta().getValue("text/deflated.txt")
        assertArrayEquals(deflated, c.readDecompressed(m))
        assertArrayEquals(deflated, c.readDecompressed(m))
    }

    @Test(expected = ZipIOException::class)
    fun readDecompressedUnknownMethodThrows() {
        val c = core()
        val m = c.ensureMeta().getValue("text/deflated.txt")
        // 伪造不支持的压缩方式
        val bad = RemoteZipCore.EntryMetadata(m.entry.copy(method = 99), m.entryOffset)
        c.readDecompressed(bad)
    }
}
