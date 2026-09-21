package io.legado.app.model.fileBook

/**
 * 远程 zip 不整包读结构核心：EOCD 定位 + central directory 解析 + 按需读压缩数据。
 * 无锁，线程安全由调用方保证（app 侧 RemoteZipWrapper 加 @Synchronized）。
 */
class RemoteZipCore(
    private val source: RangedSource,
    private val name: String,
    val fileSize: Long,
) {

    class EntryMetadata(
        val entry: ZipEntry, val entryOffset: Int, var dataOffset: Long? = null
    )

    /** 压缩数据切片，语义同 ByteArrayInputStream(bytes, offset, length) */
    class Chunk(val bytes: ByteArray, val offset: Int, val length: Int)

    private var entriesMetadata: HashMap<String, EntryMetadata>? = null
    private var closed = false
    var eocdOffset = 0L; private set
    var centralOffset = 0L; private set
    var entryCount = 0; private set

    /** 预置已缓存的目录定位，跳过 EOCD 探测 */
    fun preset(eocd: Long, central: Long, count: Int) {
        eocdOffset = eocd; centralOffset = central; entryCount = count
    }

    fun ensureMeta(): HashMap<String, EntryMetadata> {
        if (closed) throw IllegalStateException("Closed: $name")
        entriesMetadata?.let { return it }
        if (eocdOffset == 0L) {
            val sSize = if (fileSize > 0) minOf(fileSize, 65600L).toInt() else 65600
            val baseOffset = if (fileSize > 0) fileSize - sSize else 0L
            val data = source.readRange(baseOffset, sSize, fileSize)
            val pos = ZipEntryReader.findEocd(data)
            if (pos < 0) throw ZipIOException("No EOCD: $name")
            val dir = ZipEntryReader.parseEocd(data, pos, baseOffset)
            eocdOffset = dir.eocdOffset
            entryCount = dir.entryCount
            centralOffset = dir.centralOffset
        }
        val dir = source.readRange(centralOffset, (eocdOffset - centralOffset).toInt(), fileSize)
        return HashMap<String, EntryMetadata>(entryCount * 2).also { map ->
            ZipEntryReader.readCentralDirectory(dir, entryCount) { entry, _, _, _ ->
                map[entry.name] = EntryMetadata(entry, entry.entryOffset)
            }
            entriesMetadata = map
        }
    }

    fun restore(eocd: Long, central: Long, es: List<ZipEntry>) {
        eocdOffset = eocd; centralOffset = central
        entriesMetadata = HashMap<String, EntryMetadata>(es.size * 2).apply {
            es.forEach { e ->
                put(e.name, EntryMetadata(e, e.entryOffset))
            }
        }
        entryCount = es.size
    }

    fun close() {
        closed = true; entriesMetadata = null
    }

    /**
     * 读 entry 并出内容：结构读取→解压闭环。
     * method==0（STORED）原样返回，method==8（DEFLATE）走 inflateRaw，其余抛异常。
     */
    fun readDecompressed(m: EntryMetadata): ByteArray {
        val chunk = readCompressed(m)
        return when (m.entry.method) {
            0 -> if (chunk.offset == 0 && chunk.length == chunk.bytes.size) {
                chunk.bytes
            } else {
                chunk.bytes.copyOfRange(chunk.offset, chunk.offset + chunk.length)
            }

            8 -> {
                val size = m.entry.size.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: -1
                inflateRaw(chunk.bytes, chunk.offset, chunk.length, size)
            }

            else -> throw ZipIOException("Unknown compression method ${m.entry.method}: ${m.entry.name}")
        }
    }

    /** 读 entry 原始压缩数据（不解压），首次读附带解析 LOC 头并缓存数据区偏移 */
    fun readCompressed(m: EntryMetadata): Chunk {
        val entry = m.entry
        val cSize = entry.compressedSize.takeIf { it <= Int.MAX_VALUE }?.toInt()
            ?: throw ZipIOException("Too large")

        m.dataOffset?.let { dOff ->
            val bytes = source.readRange(dOff, cSize, fileSize)
            return Chunk(bytes, 0, bytes.size)
        }

        val entryOffset = m.entryOffset.toLong() and 0xffffffffL
        val totalToRead = ZipConstants.LOCHDR + entry.name.length + 128 + cSize
        val fullData = source.readRange(entryOffset, totalToRead, fileSize)

        if (fullData.size < ZipConstants.LOCHDR) throw ZipIOException("Read Header Error")

        val realExtraLen = ZipConstants.readLeShort(fullData, ZipConstants.LOCEXT)
        val realDataOff = ZipConstants.LOCHDR + entry.name.length + realExtraLen
        m.dataOffset = entryOffset + realDataOff

        val dataInBuffer = fullData.size - realDataOff
        return if (dataInBuffer >= cSize) {
            Chunk(fullData, realDataOff, cSize)
        } else {
            // 补读起点基于真实数据偏移：dataInBuffer<=0 时 buffer 尾尚未到数据区，
            // 不能从 entryOffset+fullData.size 接续（会把 extra 尾部当数据读）
            val available = maxOf(0, dataInBuffer)
            val missing = cSize - available
            val rest = source.readRange(entryOffset + realDataOff + available, missing, fileSize)
            val combined = ByteArray(cSize)
            if (available > 0) {
                fullData.copyInto(combined, 0, realDataOff, realDataOff + available)
            }
            rest.copyInto(combined, available, 0, rest.size)
            Chunk(combined, 0, combined.size)
        }
    }
}
