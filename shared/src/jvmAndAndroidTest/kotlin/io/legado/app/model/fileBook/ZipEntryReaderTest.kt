package io.legado.app.model.fileBook

import io.legado.app.model.fileBook.ZipTestFixtures.Spec
import io.legado.app.model.fileBook.ZipTestFixtures.buildZip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipEntryReaderTest {

    private val contents = listOf(
        Spec("a.txt", "hello zip ".repeat(100).toByteArray()),
        Spec("img/001.jpg", ByteArray(3000) { (it % 251).toByte() }, stored = true),
        Spec("café/naïve.png", ByteArray(64) { it.toByte() }),
    )

    @Test
    fun parseEocdAndCentralDirectory() {
        val zip = buildZip(contents)
        val pos = ZipEntryReader.findEocd(zip)
        assertTrue(pos > 0)

        val dir = ZipEntryReader.parseEocd(zip, pos)
        assertEquals(pos.toLong(), dir.eocdOffset)
        assertEquals(contents.size, dir.entryCount)
        assertTrue(dir.centralOffset in 1 until dir.eocdOffset)

        val central = zip.copyOfRange(dir.centralOffset.toInt(), dir.eocdOffset.toInt())
        val parsed = mutableListOf<ZipEntry>()
        ZipEntryReader.readCentralDirectory(central, dir.entryCount) { e, _, _, _ ->
            parsed.add(e)
        }
        // 名字（含 latin-1 非 ASCII）、尺寸、压缩方式
        assertEquals(contents.map { it.name }, parsed.map { it.name })
        contents.zip(parsed).forEach { (spec, e) ->
            assertEquals(spec.data.size.toLong(), e.size)
            assertEquals(if (spec.stored) 0 else 8, e.method)
            if (spec.stored) assertEquals(e.size, e.compressedSize)
            // entryOffset 指向 LOC 签名
            assertEquals(
                ZipConstants.LOCSIG.toInt(), ZipConstants.readLeInt(zip, e.entryOffset)
            )
        }
    }

    @Test
    fun parseEocdWithBaseOffsetFromTailSlice() {
        val zip = buildZip(contents)
        val tailSize = 200
        val base = (zip.size - tailSize).toLong()
        val tail = zip.copyOfRange(zip.size - tailSize, zip.size)

        val posInTail = ZipEntryReader.findEocd(tail)
        assertTrue(posInTail >= 0)
        val dir = ZipEntryReader.parseEocd(tail, posInTail, base)
        // 与整包解析结果一致
        val fullPos = ZipEntryReader.findEocd(zip)
        val fullDir = ZipEntryReader.parseEocd(zip, fullPos)
        assertEquals(fullDir.eocdOffset, dir.eocdOffset)
        assertEquals(fullDir.centralOffset, dir.centralOffset)
        assertEquals(fullDir.entryCount, dir.entryCount)
    }

    @Test(expected = ZipIOException::class)
    fun wrongCentralSignatureThrows() {
        ZipEntryReader.readCentralDirectory(ByteArray(64), 1) { _, _, _, _ -> }
    }

    @Test
    fun findEocdReturnsMinusOneOnGarbage() {
        assertEquals(-1, ZipEntryReader.findEocd(ByteArray(100)))
        assertEquals(-1, ZipEntryReader.findEocd(ByteArray(4)))
    }
}
