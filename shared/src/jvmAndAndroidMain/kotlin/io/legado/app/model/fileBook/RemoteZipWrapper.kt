package io.legado.app.model.fileBook

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections

/**
 * RangedSource 适配；zip 结构解析在 :modules:book-file 的 RemoteZipCore。
 * 调用方负责把 WebDav / HTTP 等远程数据源包成 RangedSource 注入。
 */
class RemoteZipWrapper(
    source: RangedSource, name: String, val fileSize: Long
) : ZipFileWrapper {

    private val core = RemoteZipCore(source, name, fileSize)

    val eocdOffset get() = core.eocdOffset
    val centralOffset get() = core.centralOffset
    val entryCount get() = core.entryCount

    constructor(
        source: RangedSource, name: String, fileSize: Long, eocd: Long, central: Long, count: Int
    ) : this(source, name, fileSize) {
        core.preset(eocd, central, count)
    }

    @Synchronized
    private fun getMeta() = core.ensureMeta()

    override fun getEntry(name: String) = getMeta()[name]?.entry
    fun getEntryOffset(name: String) = getMeta()[name]?.entryOffset
    fun preload() = getMeta()

    fun restore(eocd: Long, central: Long, es: List<ZipEntry>) = core.restore(eocd, central, es)

    override fun entries() = Collections.enumeration(getMeta().values.map { it.entry })

    override fun close() = core.close()

    override fun getInputStream(entry: ZipEntry): InputStream {
        val m = getMeta()[entry.name] ?: throw NoSuchElementException(entry.name)
        val bytes = core.readDecompressed(m)
        return ByteArrayInputStream(bytes)
    }
}
