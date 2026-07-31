package io.legado.app.help

import io.legado.app.data.entities.BaseSource
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * getZipByteArrayContent 遍历回归：旧实现每轮双进 entry，偶数位条目被跳过。
 * 构造三条目内存 zip（hex 输入走纯 JVM 分支），断言第 2/3 个都能按名取到。
 */
class GetZipByteArrayContentTest {

    private val ext = object : JsExtensionsJvm {
        override fun getSource(): BaseSource? = null
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun zipHex(vararg entries: Pair<String, ByteArray>): String {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray().toHexString()
    }

    @Test
    fun readsEveryEntryByName() {
        val a = "AAA".toByteArray()
        val b = "BBB".toByteArray()
        val c = "CCC".toByteArray()
        val hex = zipHex("a.txt" to a, "b.txt" to b, "c.txt" to c)

        assertArrayEquals(a, ext.getZipByteArrayContent(hex, "a.txt"))
        // 第 2/3 个：旧实现被 while 的双进 nextEntry 跳过
        assertArrayEquals(b, ext.getZipByteArrayContent(hex, "b.txt"))
        assertArrayEquals(c, ext.getZipByteArrayContent(hex, "c.txt"))
    }
}
