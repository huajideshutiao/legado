package io.legado.app.model.fileBook

import io.legado.app.lib.webdav.WebDav
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

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

data class ZipEntry(
    val name: String,
    val isDirectory: Boolean = false,
    val size: Long = -1,
    val compressedSize: Long = -1,
    val method: Int = -1,
    val time: Long = -1,
    val entryOffset: Int = 0
)

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
        val centralOffset = ZipConstants.readLeInt(data, pos + ZipConstants.ENDOFF).toLong() and 0xffffffffL
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
                throw IOException("Wrong Central Sig")
            }
            val method = ZipConstants.readLeShort(data, p + ZipConstants.CENHOW)
            val dostime = ZipConstants.readLeInt(data, p + ZipConstants.CENTIM)
            val csize = ZipConstants.readLeInt(data, p + ZipConstants.CENSIZ)
            val size = ZipConstants.readLeInt(data, p + ZipConstants.CENLEN)
            val nameLen = ZipConstants.readLeShort(data, p + ZipConstants.CENNAM)
            val extraLen = ZipConstants.readLeShort(data, p + ZipConstants.CENEXT)
            val commentLen = ZipConstants.readLeShort(data, p + ZipConstants.CENCOM)
            val offset = ZipConstants.readLeInt(data, p + ZipConstants.CENOFF)

            val entryName = String(data, p + ZipConstants.CENHDR, nameLen, Charsets.ISO_8859_1)
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
}

class RemoteZipWrapper(
    private val webDav: WebDav, private val name: String, val fileSize: Long
) : ZipFileWrapper {

    data class EntryMetadata(
        val entry: ZipEntry, val entryOffset: Int, var dataOffset: Long? = null
    )

    private var entriesMetadata: HashMap<String, EntryMetadata>? = null
    private var closed = false
    var eocdOffset = 0L; private set
    var centralOffset = 0L; private set
    var entryCount = 0; private set

    constructor(
        webDav: WebDav, name: String, fileSize: Long, eocd: Long, central: Long, count: Int
    ) : this(webDav, name, fileSize) {
        eocdOffset = eocd; centralOffset = central; entryCount = count
    }

    @Synchronized
    private fun getMeta(): HashMap<String, EntryMetadata> {
        if (closed) throw IllegalStateException("Closed: $name")
        entriesMetadata?.let { return it }
        if (eocdOffset == 0L) {
            val sSize = if (fileSize > 0) minOf(fileSize, 65600L).toInt() else 65600
            val baseOffset = if (fileSize > 0) fileSize - sSize else 0L
            val data = webDav.readRange(baseOffset, sSize, fileSize)
            val pos = ZipEntryReader.findEocd(data)
            if (pos < 0) throw IOException("No EOCD: $name")
            val dir = ZipEntryReader.parseEocd(data, pos, baseOffset)
            eocdOffset = dir.eocdOffset
            entryCount = dir.entryCount
            centralOffset = dir.centralOffset
        }
        val dir = webDav.readRange(centralOffset, (eocdOffset - centralOffset).toInt(), fileSize)
        return HashMap<String, EntryMetadata>(entryCount * 2).also { map ->
            ZipEntryReader.readCentralDirectory(dir, entryCount) { entry, _, _, _ ->
                map[entry.name] = EntryMetadata(entry, entry.entryOffset)
            }
            entriesMetadata = map
        }
    }

    override fun getEntry(name: String) = getMeta()[name]?.entry
    fun getEntryOffset(name: String) = getMeta()[name]?.entryOffset
    fun preload() = getMeta()

    fun restore(eocd: Long, central: Long, es: List<ZipEntry>) {
        eocdOffset = eocd; centralOffset = central
        entriesMetadata = HashMap<String, EntryMetadata>(es.size * 2).apply {
            es.forEach { e ->
                put(e.name, EntryMetadata(e, e.entryOffset))
            }
        }
        entryCount = es.size
    }

    override fun entries() = Collections.enumeration(getMeta().values.map { it.entry })

    override fun close() {
        closed = true; entriesMetadata = null
    }

    override fun getInputStream(entry: ZipEntry): InputStream? {
        val m = getMeta()[entry.name] ?: throw NoSuchElementException(entry.name)
        val cSize =
            m.entry.compressedSize.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: throw IOException(
                "Too large"
            )

        val bis = m.dataOffset?.let { dOff ->
            ByteArrayInputStream(webDav.readRange(dOff, cSize, fileSize))
        } ?: run {
            val entryOffset = m.entryOffset.toLong() and 0xffffffffL
            val totalToRead = ZipConstants.LOCHDR + entry.name.length + 128 + cSize
            val fullData = webDav.readRange(entryOffset, totalToRead, fileSize)

            if (fullData.size < ZipConstants.LOCHDR) throw IOException("Read Header Error")

            val realExtraLen = ZipConstants.readLeShort(fullData, ZipConstants.LOCEXT)
            val realDataOff = ZipConstants.LOCHDR + entry.name.length + realExtraLen
            m.dataOffset = entryOffset + realDataOff

            val dataInBuffer = fullData.size - realDataOff
            if (dataInBuffer >= cSize) {
                ByteArrayInputStream(fullData, realDataOff, cSize)
            } else {
                val missing = cSize - maxOf(0, dataInBuffer)
                val rest = webDav.readRange(entryOffset + fullData.size, missing, fileSize)
                val combined = ByteArray(cSize)
                if (dataInBuffer > 0) {
                    System.arraycopy(fullData, realDataOff, combined, 0, dataInBuffer)
                }
                System.arraycopy(rest, 0, combined, maxOf(0, dataInBuffer), rest.size)
                ByteArrayInputStream(combined)
            }
        }

        return if (m.entry.method == 8) {
            InflaterInputStream(bis, Inflater(true))
        } else bis
    }
}
