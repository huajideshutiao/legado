package io.legado.app.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.utils.File
import io.legado.app.utils.InputStream
import io.legado.app.utils.IosSecurityScopedStorage
import io.legado.app.utils.toInputStream
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * iOS 本地 cbz 的 [ZipFileWrapper]: 与 nativeMain [NativeZipFileWrapper] 同一套
 * [RemoteZipCore] 纯 Kotlin zip 解析, 但每次按需读区间都在 security-scoped 授权内进行。
 *
 * # 为什么需要
 * 用户授权的 cbz 在应用容器之外, [RemoteZipCore] 会按需读 EOCD / 中央目录 / 条目数据三段区间,
 * 这些读取必须持有 security scope 才成功。授权由 [IosSecurityScopedStorage.withAccess] 逐段起停,
 * 不与 wrapper 生命周期绑定 (漫画页可能间隔很久才翻下一页, 长期持 scope 等于把授权挂到进程上)。
 *
 * 沙盒内 cbz 由授权层自动放行, 行为与 nativeMain 实现一致。
 */
internal class IosAuthorizedZipFileWrapper(private val path: String) : ZipFileWrapper {

    private val lock = SynchronizedObject()

    private val file: File get() = File(path)

    private val core: RemoteZipCore by lazy {
        // 文件长度是文件系统调用: 外部目录需在授权内取 (之后每段区间读各自取租约)
        val size = IosSecurityScopedStorage.withAccess(path) { File(it).length() }
        RemoteZipCore(
            RangedSource { offset, length, _ -> readRange(offset, length) },
            file.name,
            size,
        )
    }

    override fun getEntry(name: String): ZipEntry? = meta()[name]?.entry

    override fun getInputStream(entry: ZipEntry): InputStream? = runCatching {
        val m = meta()[entry.name] ?: return null
        core.readDecompressed(m).toInputStream()
    }.onFailure {
        AppLog.put("读取cbz条目失败: ${entry.name}\n${it.message}", it)
    }.getOrNull()

    override fun entries(): List<ZipEntry> = meta().values.map { it.entry }

    override fun close() {
        synchronized(lock) { runCatching { core.close() } }
    }

    private fun meta() = synchronized(lock) { core.ensureMeta() }

    /** 单次区间读: 授权内整读文件后切片 (与 nativeMain 实现同策略, 不加第二套随机读逻辑)。 */
    private fun readRange(offset: Long, length: Int): ByteArray =
        IosSecurityScopedStorage.withAccess(path) { target ->
            val bytes = File(target).readBytes()
            val from = offset.coerceIn(0L, bytes.size.toLong()).toInt()
            val to = (from + length).coerceIn(from, bytes.size)
            bytes.copyOfRange(from, to)
        }
}

/**
 * iOS [ZipFileWrapperFactory]: 远程分支复用 nativeMain [NativeZipFileWrapperFactory] (WebDav 范围读
 * 与沙盒内 cbz 都不涉及 security scope), 仅本地路径分支换成授权内读区间的
 * [IosAuthorizedZipFileWrapper]。
 */
class IosZipFileWrapperFactory : ZipFileWrapperFactory {

    private val remote = NativeZipFileWrapperFactory()

    override fun create(book: Book): ZipFileWrapperFactory.CreateResult? {
        val url = book.bookUrl
        if (url.startsWith("http") || url.startsWith(BookType.webDavTag)) {
            return remote.create(book)
        }
        val path = LocalBookLocators.get().getLocalPath(book)
            ?: url.removePrefix("file://")
        val lower = path.lowercase()
        if (!lower.endsWith(".cbz") && !lower.endsWith(".zip")) return null
        val exists = runCatching {
            IosSecurityScopedStorage.withAccess(path) { File(it).isFile }
        }.getOrDefault(false)
        if (!exists) return null
        return ZipFileWrapperFactory.CreateResult(IosAuthorizedZipFileWrapper(path))
    }
}
