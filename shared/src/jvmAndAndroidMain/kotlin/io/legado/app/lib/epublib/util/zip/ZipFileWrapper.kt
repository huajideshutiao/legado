package io.legado.app.lib.epublib.util.zip

import io.legado.app.model.fileBook.RemoteZipWrapper
import java.io.IOException
import java.io.InputStream
import java.util.Enumeration
import java.util.zip.ZipFile

class ZipFileWrapper(private val zipFile: Any) {
    init {
        require(
            zipFile is ZipFile || zipFile is RemoteZipWrapper
        ) { "使用了不支持的类" }
    }

    constructor(zipFile: ZipFile) : this(zipFile as Any)
    constructor(zipFile: RemoteZipWrapper) : this(zipFile as Any)

    val name: String?
        get() = when (zipFile) {
            is ZipFile -> zipFile.name
            else -> null
        }

    val comment: String?
        get() = when (zipFile) {
            is ZipFile -> zipFile.comment
            else -> null
        }

    fun getEntry(name: String?): ZipEntryWrapper? = when (zipFile) {
        is ZipFile -> zipFile.getEntry(name)?.let { ZipEntryWrapper(it) }
        is RemoteZipWrapper -> zipFile.getEntry(name ?: "")?.let { ZipEntryWrapper(it) }
        else -> null
    }

    fun entries(): Enumeration<*>? = when (zipFile) {
        is ZipFile -> zipFile.entries()
        is RemoteZipWrapper -> zipFile.entries()
        else -> null
    }

    @Throws(IOException::class)
    fun getInputStream(entry: ZipEntryWrapper): InputStream? = when (zipFile) {
        is ZipFile -> zipFile.getInputStream(entry.getZipEntry())
        is RemoteZipWrapper -> zipFile.getInputStream(entry.remoteZipEntry)
        else -> null
    }

    @Throws(IOException::class)
    fun close() {
        when (zipFile) {
            is ZipFile -> zipFile.close()
            is RemoteZipWrapper -> zipFile.close()
        }
    }
}
