package io.legado.app.help

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.archive.ArchiveProvider
import io.legado.app.help.archive.ArchiveProviders
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * getZipByteArrayContent 取件回归：hex 输入必须原样解码成整包字节交给 [ArchiveProvider]，
 * 且每个条目都能按名取到（旧实现每轮双进 entry，偶数位条目被跳过）。
 *
 * 遍历本体已下沉 [ArchiveProviders]（Android 走 libarchive native，纯 JVM 跑不动），
 * 故这里注册一条 JDK ZipInputStream 实现顶上：断言钉住的是 JsExtensions 侧的
 * hex 解码 + 委托契约，以及“按名取件不丢条目”这条语义。
 */
class GetZipByteArrayContentTest {

    /** 记录 provider 实际收到的字节，用于验证 hex 解码没有截断/改写。 */
    private var receivedBytes: ByteArray? = null

    private val jvmZipProvider = object : ArchiveProvider {
        override val tempFolderName: String = "ArchiveTemp"

        override fun deCompress(archivePath: String): List<String> = emptyList()

        override fun getByteArrayContent(bytes: ByteArray, path: String): ByteArray? {
            receivedBytes = bytes
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == path) return zis.readBytes()
                    entry = zis.nextEntry
                }
            }
            return null
        }
    }

    private val ext = object : JsExtensionsJvm {
        override fun getSource(): BaseSource? = null
    }

    @Before
    fun setUp() {
        ArchiveProviders.register(jvmZipProvider)
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun readsEveryEntryByName() {
        val a = "AAA".toByteArray()
        val b = "BBB".toByteArray()
        val c = "CCC".toByteArray()
        val zip = zipBytes("a.txt" to a, "b.txt" to b, "c.txt" to c)
        val hex = zip.toHexString()

        assertArrayEquals(a, ext.getZipByteArrayContent(hex, "a.txt"))
        // 第 2/3 个：旧实现被 while 的双进 nextEntry 跳过
        assertArrayEquals(b, ext.getZipByteArrayContent(hex, "b.txt"))
        assertArrayEquals(c, ext.getZipByteArrayContent(hex, "c.txt"))
        // hex 分支必须把整包字节原样交给 provider
        assertArrayEquals(zip, receivedBytes)
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `包内不存在的条目返回 null`() {
        val hex = zipBytes("a.txt" to "AAA".toByteArray()).toHexString()

        assertEquals(null, ext.getZipByteArrayContent(hex, "missing.txt"))
    }
}
