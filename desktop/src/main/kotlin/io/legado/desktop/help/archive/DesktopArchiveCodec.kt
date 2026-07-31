package io.legado.desktop.help.archive

import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarVersionException
import com.github.junrar.rarfile.FileHeader
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.EncodingDetect
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 桌面端压缩包解码 (对齐 app 端 `LibArchiveUtils`, 用纯 JVM 库替代 native libarchive)。
 *
 * 格式分派: zip/7z/tar 走 commons-compress; rar 走 junrar 8.x (rar4/5/7);
 * gz/bz2/xz/lzma 视作压缩流, 内层优先按 tar 解析 (`.tar.gz` 等), 非 tar 则回退单文件。
 *
 * # 条目名编码 (对齐 LibArchiveUtils.getEntryString)
 * 有 UTF-8 标记的条目直接用其名字; 否则拿原始字节交 [EncodingDetect] 检测 (GBK 压缩包),
 * 检测失败回退 UTF-8。
 *
 * # 路径穿越
 * 与 app 端一致: 解压目标必须落在 destDir 内, 否则抛 [SecurityException]。
 */
internal object DesktopArchiveCodec {

    /** 压缩包格式校验 (对齐 app 端 `ArchiveUtils.checkArchive`)。 */
    fun checkArchive(name: String) {
        if (!AppPattern.archiveFileRegex.matches(name)) {
            throw IllegalArgumentException("Unexpected file suffix")
        }
    }

    /**
     * 解压 [archiveFile] 到 [destDir], 返回解出的文件 (对齐 `LibArchiveUtils.unArchive`)。
     *
     * [filter] 为 null 时解出全部条目; 非 null 时只解出匹配条目 (目录仍照常创建)。
     */
    fun unArchive(archiveFile: File, destDir: File, filter: ((String) -> Boolean)?): List<File> {
        val files = mutableListOf<File>()
        forEachEntry(archiveFile) { entry ->
            val entryFile = File(destDir, entry.name)
            if (!entryFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                throw SecurityException("压缩文件只能解压到指定路径")
            }
            if (entry.isDirectory) {
                if (!entryFile.exists()) entryFile.mkdirs()
                return@forEachEntry
            }
            entryFile.parentFile?.takeIf { !it.exists() }?.mkdirs()
            if (filter != null && !filter(entry.name)) return@forEachEntry
            entryFile.outputStream().use { out -> entry.copyTo(out) }
            files.add(entryFile)
        }
        return files
    }

    /** 遍历条目名 (对齐 `LibArchiveUtils.getFilesName`, 目录跳过)。 */
    fun getFilesName(archiveFile: File, filter: ((String) -> Boolean)?): List<String> {
        val names = mutableListOf<String>()
        forEachEntry(archiveFile) { entry ->
            if (entry.isDirectory) return@forEachEntry
            if (filter == null || filter(entry.name)) names.add(entry.name)
        }
        return names
    }

    /**
     * 从压缩包字节内容取 [path] 条目字节 (对齐 `LibArchiveUtils.getByteArrayContent`)。
     *
     * 无文件名可依据, 用魔数嗅探格式 ([sniffFormat]); rar/7z 需随机访问, 落临时文件后再读。
     */
    fun getByteArrayContent(bytes: ByteArray, path: String): ByteArray? {
        var result: ByteArray? = null
        forEachEntryOfBytes(bytes) { entry ->
            if (!entry.isDirectory && entry.name == path && result == null) {
                result = entry.readBytes()
            }
        }
        return result
    }

    // ---------- 条目遍历 ----------

    /** 遍历中的单个条目 (统一 zip/7z/tar/rar 差异, [copyTo]/[readBytes] 只能消费一次)。 */
    private class Entry(
        val name: String,
        val isDirectory: Boolean,
        private val open: () -> InputStream,
    ) {
        fun copyTo(out: java.io.OutputStream) = open().use { it.copyTo(out) }
        fun readBytes(): ByteArray = open().use { it.readBytes() }
    }

    private fun forEachEntry(archiveFile: File, action: (Entry) -> Unit) {
        when (formatOf(archiveFile)) {
            Format.ZIP -> forEachZipEntry(ZipFile.builder().setFile(archiveFile).get(), action)
            Format.SEVEN_Z -> forEachSevenZEntry(SevenZFile.builder().setFile(archiveFile).get(), action)
            Format.RAR -> RarCodec.forEachEntry(archiveFile, action)
            Format.TAR -> archiveFile.inputStream().buffered().use { forEachTarEntry(it, action) }
            Format.COMPRESSED -> archiveFile.inputStream().buffered().use { input ->
                forEachCompressedEntry(archiveFile.name, input, action)
            }
        }
    }

    private fun forEachEntryOfBytes(bytes: ByteArray, action: (Entry) -> Unit) {
        when (sniffFormat(bytes)) {
            Format.ZIP -> forEachZipEntry(ZipFile.builder().setByteArray(bytes).get(), action)
            Format.SEVEN_Z -> forEachSevenZEntry(SevenZFile.builder().setByteArray(bytes).get(), action)
            // junrar 从 InputStream 读时内部自行缓冲, 无需落临时文件
            Format.RAR -> RarCodec.forEachEntry(ByteArrayInputStream(bytes), action)
            Format.TAR -> forEachTarEntry(ByteArrayInputStream(bytes), action)
            Format.COMPRESSED -> forEachCompressedEntry("", ByteArrayInputStream(bytes), action)
        }
    }

    private fun forEachZipEntry(zip: ZipFile, action: (Entry) -> Unit) {
        zip.use { zipFile ->
            for (entry in zipFile.entries) {
                action(Entry(decodeZipName(entry), entry.isDirectory) { zipFile.getInputStream(entry) })
            }
        }
    }

    private fun forEachSevenZEntry(sevenZ: SevenZFile, action: (Entry) -> Unit) {
        sevenZ.use { file ->
            // 7z 条目名恒为 UTF-16 解码结果, 无原始字节可再检测
            for (entry in file.entries) {
                action(Entry(entry.name, entry.isDirectory) { file.getInputStream(entry) })
            }
        }
    }

    /**
     * tar 用 ISO-8859-1 读取条目名以保留原始字节, 再按检测到的编码重解 (GBK tar)。
     * 同 [io.legado.app.model.fileBook.CbzFile.getChapterList] 的字节回转手法。
     */
    private fun forEachTarEntry(input: InputStream, action: (Entry) -> Unit) {
        TarArchiveInputStream(input, StandardCharsets.ISO_8859_1.name()).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                action(Entry(decodeRawName(entry.name.toByteArray(StandardCharsets.ISO_8859_1)), entry.isDirectory) {
                    // tar 是顺序流, 当前条目内容只能就地读一次
                    object : InputStream() {
                        override fun read() = tar.read()
                        override fun read(b: ByteArray, off: Int, len: Int) = tar.read(b, off, len)
                        override fun close() {} // 不关外层 tar 流
                    }
                })
            }
        }
    }

    /**
     * gz/bz2/xz/lzma: 解压后内层优先按 tar 解析 (`book.tar.gz`), 非 tar 则当单文件条目。
     *
     * 单文件条目名 = 去掉压缩后缀的原名 (`book.txt.gz` → `book.txt`), 无原名时用 "content"。
     */
    private fun forEachCompressedEntry(archiveName: String, input: InputStream, action: (Entry) -> Unit) {
        val decompressed = BufferedInputStream(decompress(input))
        // 探测 tar 头 (512 字节) 后回退流位置, 不整包读进内存
        decompressed.mark(TAR_HEADER_SIZE)
        val header = decompressed.readNBytes(TAR_HEADER_SIZE)
        decompressed.reset()
        if (isTar(header)) {
            forEachTarEntry(decompressed, action)
            return
        }
        val name = archiveName.substringBeforeLast('.', "").ifEmpty { "content" }
        action(Entry(name, false) { decompressed })
    }

    /** 按魔数选压缩流实现 (gz/bz2/xz 有固定标记, .lzma 无标记走兜底)。 */
    private fun decompress(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input)
        buffered.mark(6)
        val magic = buffered.readNBytes(6)
        buffered.reset()
        return when {
            startsWith(magic, 0x1f, 0x8b) -> GzipCompressorInputStream(buffered, true)
            startsWith(magic, 0x42, 0x5a, 0x68) -> BZip2CompressorInputStream(buffered, true)
            startsWith(magic, 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00) -> XZCompressorInputStream(buffered, true)
            else -> LZMACompressorInputStream(buffered)
        }
    }

    // ---------- 格式判定 ----------

    private enum class Format { ZIP, SEVEN_Z, RAR, TAR, COMPRESSED }

    /** 按后缀定格式 (与 [AppPattern.archiveFileRegex] 支持范围一致), 未知后缀报错。 */
    private fun formatOf(archiveFile: File): Format {
        val name = archiveFile.name
        checkArchive(name)
        return when (name.substringAfterLast('.', "").lowercase()) {
            "zip" -> Format.ZIP
            "7z" -> Format.SEVEN_Z
            "rar" -> Format.RAR
            "tar" -> Format.TAR
            "gz", "bz2", "xz", "lzma" -> Format.COMPRESSED
            else -> throw IllegalArgumentException("Unexpected file suffix")
        }
    }

    /** 无文件名场景按魔数嗅探 (zip/7z/rar/tar 各有固定标记, 其余按压缩流处理)。 */
    private fun sniffFormat(bytes: ByteArray): Format = when {
        startsWith(bytes, 0x50, 0x4b) -> Format.ZIP
        startsWith(bytes, 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c) -> Format.SEVEN_Z
        startsWith(bytes, 0x52, 0x61, 0x72, 0x21) -> Format.RAR
        isTar(bytes) -> Format.TAR
        else -> Format.COMPRESSED
    }

    private const val TAR_HEADER_SIZE = 512

    /** tar 标记: 偏移 257 处 "ustar"。 */
    private fun isTar(bytes: ByteArray): Boolean {
        if (bytes.size < 262) return false
        return String(bytes, 257, 5, StandardCharsets.US_ASCII) == "ustar"
    }

    private fun startsWith(bytes: ByteArray, vararg magic: Int): Boolean {
        if (bytes.size < magic.size) return false
        return magic.withIndex().all { (i, b) -> bytes[i] == b.toByte() }
    }

    // ---------- 条目名解码 ----------

    /** zip: 有 UTF-8 标记用条目名, 否则拿原始字节检测 (对齐 LibArchiveUtils.getEntryString)。 */
    private fun decodeZipName(entry: ZipArchiveEntry): String {
        if (entry.generalPurposeBit.usesUTF8ForNames()) return entry.name
        val raw = entry.rawName ?: return entry.name
        return decodeRawName(raw)
    }

    /** 原始字节 → 字符串: 走 [EncodingDetect] 检测编码, 未知/不支持回退 UTF-8。 */
    private fun decodeRawName(raw: ByteArray): String {
        val charsetName = runCatching { EncodingDetect.getEncode(raw) }.getOrNull()
        val charset = charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() }
        return String(raw, charset ?: StandardCharsets.UTF_8)
    }

    /**
     * rar 解码 (junrar 8.x, rar4/rar5/rar7 均支持)。
     *
     * 更高版本格式抛 [UnsupportedRarVersionException], 这里转成明确文案而非静默失败。
     */
    private object RarCodec {

        fun forEachEntry(archiveFile: File, action: (Entry) -> Unit) =
            forEach({ Archive(archiveFile) }, archiveFile.name, action)

        fun forEachEntry(input: InputStream, action: (Entry) -> Unit) =
            forEach({ Archive(input) }, "", action)

        private fun forEach(open: () -> Archive, name: String, action: (Entry) -> Unit) {
            val archive = try {
                open()
            } catch (e: UnsupportedRarVersionException) {
                throw NoStackTraceException("不支持的 rar 版本: $name").apply { addSuppressed(e) }
            }
            archive.use { rar ->
                for (header in rar.fileHeaders) {
                    action(Entry(rarEntryName(header), header.isDirectory) { rar.getInputStream(header) })
                }
            }
        }

        /** unicode 头用其解码结果; 非 unicode 头 (GBK 等) 拿原始字节走检测。 */
        @Suppress("DEPRECATION")
        private fun rarEntryName(header: FileHeader): String {
            if (header.isUnicode) return header.fileName
            val raw = runCatching { header.fileNameByteArray }.getOrNull()
            return if (raw != null && raw.isNotEmpty()) decodeRawName(raw) else header.fileName
        }
    }
}
