package io.legado.app.model.fileBook

import kotlinx.serialization.Serializable

object ZipConstants {
    const val LOCSIG = 0x04034b50L
    const val LOCHDR = 30
    const val LOCNAM = 26
    const val LOCEXT = 28
    const val CENSIG = 0x02014b50L
    const val CENHDR = 46
    const val CENHOW = 10
    const val CENTIM = 12
    const val CENSIZ = 20
    const val CENLEN = 24
    const val CENNAM = 28
    const val CENEXT = 30
    const val CENCOM = 32
    const val CENOFF = 42
    const val ENDSIG = 0x06054b50L
    const val ENDHDR = 22
    const val ENDTOT = 10
    const val ENDOFF = 16

    fun readLeShort(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xff) or (b[off + 1].toInt() and 0xff shl 8)
    }

    fun readLeInt(b: ByteArray, off: Int): Int {
        return readLeShort(b, off) or (readLeShort(b, off + 2) shl 16)
    }
}

@Serializable
data class ZipEntry(
    val name: String,
    val isDirectory: Boolean = false,
    val size: Long = -1,
    val compressedSize: Long = -1,
    val method: Int = -1,
    val time: Long = -1,
    val entryOffset: Int = 0
)

@Serializable
data class ZipImageCache(
    val entries: List<ZipEntry>, val eocdOffset: Long = 0, val centralOffset: Long = 0
)

data class ZipCentralDir(
    val eocdOffset: Long,
    val centralOffset: Long,
    val entryCount: Int
)

object ZipEntryReader {
    fun findEocd(data: ByteArray): Int {
        return (data.size - ZipConstants.ENDHDR downTo 0).firstOrNull {
            ZipConstants.readLeInt(data, it) == ZipConstants.ENDSIG.toInt()
        } ?: -1
    }

    fun parseEocd(data: ByteArray, pos: Int, baseOffset: Long = 0): ZipCentralDir {
        val count = ZipConstants.readLeShort(data, pos + ZipConstants.ENDTOT)
        val centralOffset =
            ZipConstants.readLeInt(data, pos + ZipConstants.ENDOFF).toLong() and 0xffffffffL
        return ZipCentralDir(
            eocdOffset = baseOffset + pos,
            centralOffset = centralOffset,
            entryCount = count
        )
    }

    fun readCentralDirectory(
        data: ByteArray,
        entryCount: Int,
        onEntry: (entry: ZipEntry, nameLen: Int, extraLen: Int, commentLen: Int) -> Unit
    ) {
        var p = 0
        repeat(entryCount) {
            if (ZipConstants.readLeInt(data, p) != ZipConstants.CENSIG.toInt()) {
                throw ZipIOException("Wrong Central Sig")
            }
            val method = ZipConstants.readLeShort(data, p + ZipConstants.CENHOW)
            val dostime = ZipConstants.readLeInt(data, p + ZipConstants.CENTIM)
            val csize = ZipConstants.readLeInt(data, p + ZipConstants.CENSIZ)
            val size = ZipConstants.readLeInt(data, p + ZipConstants.CENLEN)
            val nameLen = ZipConstants.readLeShort(data, p + ZipConstants.CENNAM)
            val extraLen = ZipConstants.readLeShort(data, p + ZipConstants.CENEXT)
            val commentLen = ZipConstants.readLeShort(data, p + ZipConstants.CENCOM)
            val offset = ZipConstants.readLeInt(data, p + ZipConstants.CENOFF)

            val entryName = data.decodeLatin1(p + ZipConstants.CENHDR, nameLen)
            val entry = ZipEntry(
                name = entryName,
                isDirectory = false,
                method = method,
                size = size.toLong() and 0xffffffffL,
                compressedSize = csize.toLong() and 0xffffffffL,
                time = dostime.toLong(),
                entryOffset = offset
            )
            onEntry(entry, nameLen, extraLen, commentLen)
            p += ZipConstants.CENHDR + nameLen + extraLen + commentLen
        }
    }

    /** ISO-8859-1 解码，等价 java String(bytes, ISO_8859_1) */
    private fun ByteArray.decodeLatin1(offset: Int, length: Int): String {
        return CharArray(length) { (this[offset + it].toInt() and 0xff).toChar() }.concatToString()
    }
}
