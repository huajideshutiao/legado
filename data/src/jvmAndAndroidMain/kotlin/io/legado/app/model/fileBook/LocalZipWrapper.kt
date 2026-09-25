package io.legado.app.model.fileBook

import io.legado.app.utils.InputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * 本地 .cbz/.zip 文件的 [ZipFileWrapper] 实现 (JVM/Android 共用)。
 *
 * 条目流用 `java.io.InputStream` (JDK `ZipFile.getInputStream` 直出); 契约声明的
 * [io.legado.app.utils.InputStream] 在 jvmAndAndroidMain 是 `typealias` 到 java.io.InputStream,
 * 故此处直接返回 JDK 流即可, 无需包装。
 */
class LocalZipWrapper(private val file: File) : ZipFileWrapper {
    private var zipFile: ZipFile? = null

    private fun getZipFile(): ZipFile {
        return zipFile ?: ZipFile(file, Charsets.ISO_8859_1).also { zipFile = it }
    }

    override fun getEntry(name: String): ZipEntry? {
        return getZipFile().getEntry(name)?.let {
            ZipEntry(
                it.name, it.isDirectory, it.size, it.compressedSize, it.method, it.time
            )
        }
    }

    override fun getInputStream(entry: ZipEntry): InputStream? {
        return getZipFile().let { zf ->
            zf.getEntry(entry.name)?.let { zEntry ->
                zf.getInputStream(zEntry)
            }
        }
    }

    override fun entries(): List<ZipEntry> {
        return getZipFile().entries().asSequence().map {
            ZipEntry(
                it.name, it.isDirectory, it.size, it.compressedSize, it.method, it.time
            )
        }.toList()
    }

    override fun close() {
        zipFile?.close()
        zipFile = null
    }
}
