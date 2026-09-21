package io.legado.app.model.fileBook

import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipFile

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

    override fun entries(): Enumeration<out ZipEntry> {
        return Collections.enumeration(getZipFile().entries().asSequence().map {
            ZipEntry(
                it.name, it.isDirectory, it.size, it.compressedSize, it.method, it.time
            )
        }.toList())
    }

    override fun close() {
        zipFile?.close()
        zipFile = null
    }
}
