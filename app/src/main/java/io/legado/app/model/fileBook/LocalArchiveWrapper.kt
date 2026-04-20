package io.legado.app.model.fileBook

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import io.legado.app.utils.compress.LibArchiveUtils
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections
import java.util.Enumeration

class LocalArchiveWrapper(private val pfd: ParcelFileDescriptor) : ZipFileWrapper {
    private var entriesMap: Map<String, ZipEntry>? = null

    private fun getEntriesMap(): Map<String, ZipEntry> {
        return entriesMap ?: LibArchiveUtils.getFilesName(pfd, null)
            .associateWith { ZipEntry(it, false) }
            .also { entriesMap = it }
    }

    override fun getEntry(name: String): ZipEntry? = getEntriesMap()[name]

    override fun getInputStream(entry: ZipEntry): InputStream? {
        val dupPfd = pfd.dup()
        Os.lseek(dupPfd.fileDescriptor, 0, OsConstants.SEEK_SET)
        val data = ParcelFileDescriptor.AutoCloseInputStream(dupPfd).use {
            LibArchiveUtils.getByteArrayContent(it, entry.name)
        }
        return data?.let { ByteArrayInputStream(it) }
    }

    override fun entries(): Enumeration<out ZipEntry> =
        Collections.enumeration(getEntriesMap().values)

    override fun close() {}
}
