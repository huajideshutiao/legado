package io.legado.app.model.fileBook

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import io.legado.app.constant.AppLog
import java.io.EOFException
import java.io.InputStream
import java.util.Collections
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

class ContentZipWrapper(private val pfd: ParcelFileDescriptor) : ZipFileWrapper {
    private var entriesMap: HashMap<String, ZipEntry>? = null
    private val entryOffsets = HashMap<String, Int>()

    private fun seek(pos: Long) = Os.lseek(pfd.fileDescriptor, pos, OsConstants.SEEK_SET)

    private fun currentPosition() = Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR)

    private fun length() = Os.fstat(pfd.fileDescriptor).st_size

    private fun readFully(b: ByteArray, off: Int = 0, len: Int = b.size) {
        var n = 0
        while (n < len) {
            val count = Os.read(pfd.fileDescriptor, b, off + n, len - n)
            if (count < 0) throw EOFException()
            n += count
        }
    }

    @Synchronized
    private fun readEntries(): HashMap<String, ZipEntry> {
        entriesMap?.let { return it }

        val fileLen = length()
        val sSize = minOf(fileLen, 65536L + ZipConstants.ENDHDR).toInt()
        val data = ByteArray(sSize)
        val sPos = fileLen - sSize
        seek(sPos)
        readFully(data)
        val pos = ZipEntryReader.findEocd(data)
        if (pos < 0) throw ZipException("central directory not found")

        val dir = ZipEntryReader.parseEocd(data, pos, sPos)
        val count = dir.entryCount
        val centralOffset = dir.centralOffset

        val entries = HashMap<String, ZipEntry>(count + count / 2)
        seek(centralOffset)
        val ebs = ByteArray(ZipConstants.CENHDR)

        repeat(count) {
            readFully(ebs)
            if (ZipConstants.readLeInt(ebs, 0) != ZipConstants.CENSIG.toInt())
                throw ZipException("Wrong Central Directory signature")

            val method = ZipConstants.readLeShort(ebs, ZipConstants.CENHOW)
            val dostime = ZipConstants.readLeInt(ebs, ZipConstants.CENTIM)
            val csize = ZipConstants.readLeInt(ebs, ZipConstants.CENSIZ)
            val size = ZipConstants.readLeInt(ebs, ZipConstants.CENLEN)
            val nameLen = ZipConstants.readLeShort(ebs, ZipConstants.CENNAM)
            val extraLen = ZipConstants.readLeShort(ebs, ZipConstants.CENEXT)
            val commentLen = ZipConstants.readLeShort(ebs, ZipConstants.CENCOM)
            val offset = ZipConstants.readLeInt(ebs, ZipConstants.CENOFF)

            val nameBytes = ByteArray(nameLen)
            readFully(nameBytes)
            val name = String(nameBytes, Charsets.ISO_8859_1)

            val skipLen = extraLen + commentLen
            if (skipLen > 0) {
                seek(currentPosition() + skipLen)
            }

            entries[name] = ZipEntry(
                name = name,
                isDirectory = false,
                size = size.toLong() and 0xffffffffL,
                compressedSize = csize.toLong() and 0xffffffffL,
                method = method,
                time = dostime.toLong()
            ).also {
                entryOffsets[name] = offset
            }
        }

        entriesMap = entries
        return entries
    }

    override fun getEntry(name: String) = readEntries()[name]

    override fun getInputStream(entry: ZipEntry): InputStream? {
        return try {
            val offset = entryOffsets[entry.name] ?: return null
            synchronized(pfd) {
                val entryOffset = offset.toLong() and 0xffffffffL
                seek(entryOffset)
                val locBuf = ByteArray(ZipConstants.LOCHDR)
                readFully(locBuf)

                if (ZipConstants.readLeInt(locBuf, 0) != ZipConstants.LOCSIG.toInt())
                    throw ZipException("Wrong Local header signature")

                val nameLen = ZipConstants.readLeShort(locBuf, ZipConstants.LOCNAM)
                val extraLen = ZipConstants.readLeShort(locBuf, ZipConstants.LOCEXT)
                val dataOffset = entryOffset + ZipConstants.LOCHDR + nameLen + extraLen

                val inputStream =
                    PartialInputStream(pfd, dataOffset, dataOffset + entry.compressedSize)
                when (entry.method) {
                    0 -> inputStream
                    8 -> InflaterInputStream(inputStream, Inflater(true))
                    else -> throw ZipException("Unknown compression method ${entry.method}")
                }
            }
        } catch (e: Exception) {
            AppLog.put("ContentZipWrapper getInputStream Error: ${e.localizedMessage}", e)
            null
        }
    }

    override fun entries() = Collections.enumeration(readEntries().values)

    override fun close() {}

    private class PartialInputStream(
        private val pfd: ParcelFileDescriptor, private var filepos: Long, private val end: Long
    ) : InputStream() {
        override fun available(): Int = minOf((end - filepos).toInt(), Int.MAX_VALUE)

        override fun read(): Int {
            if (filepos >= end) return -1
            return synchronized(pfd) {
                Os.lseek(pfd.fileDescriptor, filepos++, OsConstants.SEEK_SET)
                val b = ByteArray(1)
                val count = Os.read(pfd.fileDescriptor, b, 0, 1)
                if (count < 0) -1 else b[0].toInt() and 0xff
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (filepos >= end) return -1
            var length = len
            if (length > end - filepos) {
                length = (end - filepos).toInt()
            }
            return synchronized(pfd) {
                Os.lseek(pfd.fileDescriptor, filepos, OsConstants.SEEK_SET)
                val count = Os.read(pfd.fileDescriptor, b, off, length)
                if (count > 0) filepos += count
                count
            }
        }

        override fun skip(n: Long): Long {
            var amount = n
            if (amount > end - filepos) amount = end - filepos
            filepos += amount
            return amount
        }
    }
}
