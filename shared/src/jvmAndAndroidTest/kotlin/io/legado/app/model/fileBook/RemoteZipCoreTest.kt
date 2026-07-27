package io.legado.app.model.fileBook

import io.legado.app.model.fileBook.ZipTestFixtures.Spec
import io.legado.app.model.fileBook.ZipTestFixtures.buildZip
import io.legado.app.model.fileBook.ZipTestFixtures.decode
import io.legado.app.model.fileBook.ZipTestFixtures.extraField
import io.legado.app.model.fileBook.ZipTestFixtures.sourceOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteZipCoreTest {

    private val textData = "远程zip整链路 test data ".repeat(200).toByteArray()
    private val imgData = ByteArray(5000) { (it * 7 % 251).toByte() }
    private val specs = listOf(
        Spec("chapter1/0001.txt", textData),
        Spec("chapter1/0002.jpg", imgData, stored = true),
    )
    private val zip = buildZip(specs)

    private fun coreOf(
        bytes: ByteArray = zip,
        fileSize: Long = bytes.size.toLong(),
        log: MutableList<Long>? = null,
    ) = RemoteZipCore(sourceOf(bytes, log), "test.zip", fileSize)

    @Test
    fun fullChainWithKnownFileSize() {
        val core = coreOf()
        val meta = core.ensureMeta()

        assertEquals(specs.size, core.entryCount)
        assertTrue(core.eocdOffset > core.centralOffset)
        assertEquals(specs.map { it.name }.toSet(), meta.keys)

        specs.forEach { spec ->
            val m = meta.getValue(spec.name)
            val content = decode(core.readCompressed(m), m.entry.method)
            assertArrayEquals(spec.data, content)
        }
    }

    @Test
    fun fullChainWithUnknownFileSize() {
        val core = coreOf(fileSize = 0)
        val meta = core.ensureMeta()
        assertEquals(specs.size, meta.size)
        val m = meta.getValue("chapter1/0001.txt")
        assertArrayEquals(textData, decode(core.readCompressed(m), m.entry.method))
    }

    @Test
    fun secondReadUsesCachedDataOffset() {
        val core = coreOf()
        val m = core.ensureMeta().getValue("chapter1/0002.jpg")

        val log = mutableListOf<Long>()
        val spied = RemoteZipCore(sourceOf(zip, log), "test.zip", zip.size.toLong())
        val m2 = spied.ensureMeta().getValue("chapter1/0002.jpg")
        decode(spied.readCompressed(m2), m2.entry.method)
        val dataOffset = m2.dataOffset!!
        log.clear()
        // 第二次读：仅 1 次 range 请求，直达数据区
        assertArrayEquals(imgData, decode(spied.readCompressed(m2), m2.entry.method))
        assertEquals(listOf(dataOffset), log)
        assertEquals(m.entry, m2.entry)
    }

    @Test
    fun presetSkipsEocdProbe() {
        val probe = coreOf()
        probe.ensureMeta()

        val log = mutableListOf<Long>()
        val core = coreOf(log = log)
        core.preset(probe.eocdOffset, probe.centralOffset, probe.entryCount)
        val meta = core.ensureMeta()
        // 首个请求直达 central directory，无 EOCD 尾部探测
        assertEquals(probe.centralOffset, log.first())
        assertEquals(specs.size, meta.size)
    }

    @Test
    fun restoreSkipsAllStructureReads() {
        val probe = coreOf()
        val entries = probe.ensureMeta().values.map { it.entry }

        val log = mutableListOf<Long>()
        val core = coreOf(log = log)
        core.restore(probe.eocdOffset, probe.centralOffset, entries)
        val meta = core.ensureMeta()
        assertTrue(log.isEmpty())
        assertEquals(probe.eocdOffset, core.eocdOffset)
        assertEquals(probe.centralOffset, core.centralOffset)
        assertEquals(entries.size, core.entryCount)

        val m = meta.getValue("chapter1/0001.txt")
        assertArrayEquals(textData, decode(core.readCompressed(m), m.entry.method))
    }

    @Test
    fun bigLocalExtraFieldTriggersTailCompletion() {
        // LOC extra 超过 128 字节余量，命中补读分支（dataInBuffer>0，cSize 大于 extra-128 溢出量）。
        // dataInBuffer<=0 的包络见 hugeLocalExtraWithSmallEntryReadsFromRealDataOffset。
        val data = ByteArray(5000) { (it * 31 + 7).toByte() } // stored，cSize 确定为 5000
        val bigExtraZip =
            buildZip(listOf(Spec("e.txt", data, stored = true, extra = extraField(300))))
        val core = coreOf(bigExtraZip)
        val m = core.ensureMeta().getValue("e.txt")
        assertArrayEquals(data, decode(core.readCompressed(m), m.entry.method))
        // 补读后偏移缓存生效，重复读一致
        assertArrayEquals(data, decode(core.readCompressed(m), m.entry.method))
    }

    @Test
    fun hugeLocalExtraWithSmallEntryReadsFromRealDataOffset() {
        // LOC extra 溢出 128 余量且压缩体小于溢出量（dataInBuffer<0）：
        // 首读 buffer 尾未达数据区，补读必须从真实数据偏移开始，而非 entryOffset+fullData.size。
        val data = ByteArray(100) { (it * 13 + 3).toByte() } // stored，cSize=100 < 300-128
        val zip = buildZip(listOf(Spec("e.txt", data, stored = true, extra = extraField(300))))
        val core = coreOf(zip)
        val m = core.ensureMeta().getValue("e.txt")
        assertArrayEquals(data, decode(core.readCompressed(m), m.entry.method))
        // 偏移缓存后重复读一致
        assertArrayEquals(data, decode(core.readCompressed(m), m.entry.method))
    }

    @Test(expected = IllegalStateException::class)
    fun closedCoreThrows() {
        val core = coreOf()
        core.close()
        core.ensureMeta()
    }

    @Test(expected = ZipIOException::class)
    fun garbageBytesThrowNoEocd() {
        coreOf(ByteArray(500)).ensureMeta()
    }
}
