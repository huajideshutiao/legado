package io.legado.app.model.fileBook

import io.legado.app.utils.InputStream
import io.legado.app.utils.toInputStream
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * RangedSource 适配；zip 结构解析在 commonMain 的 [RemoteZipCore]。
 * 调用方负责把 WebDav / HTTP 等远程数据源包成 RangedSource 注入。
 *
 * commonMain 下沉版 (原在 jvmAndAndroidMain): [CbzFile] 下沉后需在四端共用本类,
 * 故把 `java.io.ByteArrayInputStream` 换成 [toInputStream] 门面、
 * `@Synchronized` 换成 [SynchronizedObject]。
 */
class RemoteZipWrapper(
    source: RangedSource, name: String, val fileSize: Long
) : ZipFileWrapper {

    private val core = RemoteZipCore(source, name, fileSize)

    private val lock = SynchronizedObject()

    val eocdOffset get() = core.eocdOffset
    val centralOffset get() = core.centralOffset
    val entryCount get() = core.entryCount

    constructor(
        source: RangedSource, name: String, fileSize: Long, eocd: Long, central: Long, count: Int
    ) : this(source, name, fileSize) {
        core.preset(eocd, central, count)
    }

    private fun getMeta() = synchronized(lock) { core.ensureMeta() }

    override fun getEntry(name: String) = getMeta()[name]?.entry
    fun getEntryOffset(name: String) = getMeta()[name]?.entryOffset
    fun preload() = getMeta()

    fun restore(eocd: Long, central: Long, es: List<ZipEntry>) = core.restore(eocd, central, es)

    override fun entries() = getMeta().values.map { it.entry }

    override fun close() = core.close()

    override fun getInputStream(entry: ZipEntry): InputStream {
        val m = getMeta()[entry.name] ?: throw NoSuchElementException(entry.name)
        val bytes = core.readDecompressed(m)
        return bytes.toInputStream()
    }
}
