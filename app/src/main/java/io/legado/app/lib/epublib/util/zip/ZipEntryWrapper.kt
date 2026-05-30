package io.legado.app.lib.epublib.util.zip

import java.util.zip.ZipEntry

class ZipEntryWrapper(private val zipEntry: Any) {
    init {
        require(zipEntry is ZipEntry || zipEntry is AndroidZipEntry) { "使用了不支持的类" }
    }

    constructor(zipEntry: ZipEntry) : this(zipEntry as Any)
    constructor(zipEntry: AndroidZipEntry) : this(zipEntry as Any)

    val isDirectory: Boolean
        get() = when (zipEntry) {
            is ZipEntry -> zipEntry.isDirectory
            is AndroidZipEntry -> zipEntry.isDirectory
            else -> true
        }

    fun getZipEntry(): ZipEntry = zipEntry as ZipEntry

    val androidZipEntry: AndroidZipEntry get() = zipEntry as AndroidZipEntry

    val name: String?
        get() = when (zipEntry) {
            is ZipEntry -> zipEntry.name
            is AndroidZipEntry -> zipEntry.name
            else -> null
        }

    val size: Long
        get() = when (zipEntry) {
            is ZipEntry -> zipEntry.size
            is AndroidZipEntry -> zipEntry.getSize()
            else -> -1
        }
}
