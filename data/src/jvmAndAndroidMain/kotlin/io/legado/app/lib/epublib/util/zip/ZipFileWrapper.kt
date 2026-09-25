package io.legado.app.lib.epublib.util.zip

import io.legado.app.model.fileBook.RemoteZipWrapper
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipFile

class ZipFileWrapper(private val zipFile: Any) {
    init {
        require(
            zipFile is ZipFile || zipFile is AndroidZipFileReader || zipFile is RemoteZipWrapper
        ) { "使用了不支持的类" }
    }

    constructor(zipFile: ZipFile) : this(zipFile as Any)
    constructor(zipFile: AndroidZipFileReader) : this(zipFile as Any)
    constructor(zipFile: RemoteZipWrapper) : this(zipFile as Any)

    val name: String?
        get() = when (zipFile) {
            is ZipFile -> zipFile.name
            is AndroidZipFileReader -> zipFile.name
            else -> null
        }

    val comment: String?
        get() = when (zipFile) {
            is ZipFile -> zipFile.comment
            is AndroidZipFileReader -> zipFile.name
            else -> null
        }

    fun getEntry(name: String?): ZipEntryWrapper? = when (zipFile) {
        is ZipFile -> zipFile.getEntry(name)?.let { ZipEntryWrapper(it) }
        is AndroidZipFileReader -> zipFile.getEntry(name)?.let { ZipEntryWrapper(it) }
        is RemoteZipWrapper -> zipFile.getEntry(name ?: "")?.let { ZipEntryWrapper(it) }
        else -> null
    }

    fun entries(): Enumeration<*>? = when (zipFile) {
        is ZipFile -> zipFile.entries()
        is AndroidZipFileReader -> zipFile.entries()
        // RemoteZipWrapper.entries() 已改为返回 List (契约下沉 commonMain 时去掉了
        // java.util.Enumeration); 本类面向 epublib 保持 Enumeration 表面, 在此适配。
        is RemoteZipWrapper -> Collections.enumeration(zipFile.entries())
        else -> null
    }

    @Throws(IOException::class)
    fun getInputStream(entry: ZipEntryWrapper): InputStream? = when (zipFile) {
        is ZipFile -> zipFile.getInputStream(entry.getZipEntry())
        is AndroidZipFileReader -> zipFile.getInputStream(entry.androidZipEntry)
        is RemoteZipWrapper -> zipFile.getInputStream(entry.remoteZipEntry)
        else -> null
    }

    @Throws(IOException::class)
    fun close() {
        when (zipFile) {
            is ZipFile -> zipFile.close()
            is AndroidZipFileReader -> zipFile.close()
            is RemoteZipWrapper -> zipFile.close()
        }
    }
}
