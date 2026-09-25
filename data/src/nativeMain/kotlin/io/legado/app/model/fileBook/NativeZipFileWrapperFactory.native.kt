package io.legado.app.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.utils.File
import io.legado.app.utils.InputStream
import io.legado.app.utils.toInputStream
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * 本地 cbz 的 [ZipFileWrapper] 实现 (iOS/鸿蒙共用)。
 *
 * 与 JVM 侧 `LocalZipWrapper` (java.util.zip.ZipFile) 等价, 但用 commonMain 的
 * [RemoteZipCore] 做纯 Kotlin zip 解析 —— native 端无 java.util.zip, 这是唯一的
 * 本地 zip 读取途径 (与 [io.legado.app.help.storage.NativeZipCodec] /
 * [io.legado.app.help.archive.NativeArchiveProvider] 同一套底层能力)。
 *
 * 每次 [getInputStream] 按需读该条目的压缩区间并解压 (RemoteZipCore.readDecompressed 语义);
 * cbz 漫画页的调用方 (ReaderImageResolver 经 `cbz://` URL) 自带内存 LRU, 不在此层加缓存。
 *
 * @param filePath 本地 cbz 绝对路径 (file:// 已剥离)
 */
internal class NativeZipFileWrapper(private val filePath: String) : ZipFileWrapper {

    private val lock = SynchronizedObject()

    private val file: File by lazy { File(filePath) }

    private val core: RemoteZipCore by lazy {
        RemoteZipCore(
            RangedSource { offset, length, _ -> readRange(offset, length) },
            file.name,
            file.length(),
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

    /**
     * 按需读取文件区间。
     *
     * 每次整读文件再切片: native 端无随机读文件句柄 (okio FileSystem 只有全量 read/write),
     * 而 RemoteZipCore 只请求 EOCD/中央目录/单条目压缩数据三小段, 整读后切片与逐段读语义一致,
     * 且 cbz 体积受漫画单册限制。若后续出现大体积 cbz 性能问题, 再换 okio FileHandle 随机读。
     */
    private fun readRange(offset: Long, length: Int): ByteArray {
        val bytes = file.readBytes()
        val from = offset.coerceIn(0L, bytes.size.toLong()).toInt()
        val to = (from + length).coerceIn(from, bytes.size)
        return bytes.copyOfRange(from, to)
    }
}

/**
 * iOS/鸿蒙的 [ZipFileWrapperFactory] 实现 (nativeMain 共用)。
 *
 * # 分支语义与 app 端 `ZipFileWrappers.create` 对齐
 * - WebDAV / HTTP: [RemoteZipWrapper] + WebDav 范围读取 (与 desktop / app 同语义)
 * - 本地 .cbz/.zip: [NativeZipFileWrapper]
 * - 其他本地压缩包: native 端无 libarchive 解码库, 返回 null (调用方按失败处理,
 *   与 `NativeArchiveProvider` 对 rar/7z 抛明确异常的口径一致)
 *
 * 在 iOS `IosProviderRegistry` / 鸿蒙 `OhosProviderRegistry` 启动早期注册一次
 * (任何 cbz 解析之前)。
 */
class NativeZipFileWrapperFactory : ZipFileWrapperFactory {

    override fun create(book: Book): ZipFileWrapperFactory.CreateResult? = runCatching {
        val url = book.bookUrl
        when {
            url.startsWith(BookType.webDavTag) || url.startsWith("http") -> createRemote(book)
            else -> createLocal(url)
        }
    }.onFailure {
        AppLog.put("读取Cbz文件失败\n${it.message}", it)
    }.getOrNull()

    private fun createRemote(book: Book): ZipFileWrapperFactory.CreateResult {
        val url = book.getRemoteUrl() ?: book.bookUrl
        val webDav = runCatching { WebDav.fromPath(url) }.getOrElse {
            AppWebDavShared.authorization?.let { auth -> WebDav(url, auth) }
                ?: throw WebDavException("No Auth")
        }
        var eocd = 0L
        var central = 0L
        var size = 0L
        book.variable?.takeIf { it.startsWith("cbz:") }?.substring(4)?.split(",")
            ?.let { p ->
                eocd = p.getOrNull(0)?.toLongOrNull() ?: 0L
                central = p.getOrNull(1)?.toLongOrNull() ?: 0L
                size = p.getOrNull(2)?.toLongOrNull() ?: 0L
            }
        val count = book.wordCount?.replace("页", "")?.toIntOrNull() ?: 0
        val source = RangedSource { offset, length, fileSize ->
            webDav.readRange(offset, length, fileSize)
        }
        val wrapper = if (eocd > 0 && central > 0 && count > 0) {
            RemoteZipWrapper(source, book.originName, size, eocd, central, count)
        } else {
            RemoteZipWrapper(source, book.originName, size)
        }
        return ZipFileWrapperFactory.CreateResult(wrapper)
    }

    private fun createLocal(bookUrl: String): ZipFileWrapperFactory.CreateResult? {
        val lower = bookUrl.lowercase()
        if (!lower.endsWith(".cbz") && !lower.endsWith(".zip")) return null
        val path = bookUrl.removePrefix("file://")
        if (path.isEmpty()) return null
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        return ZipFileWrapperFactory.CreateResult(NativeZipFileWrapper(path))
    }
}
