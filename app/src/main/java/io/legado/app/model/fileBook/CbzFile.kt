package io.legado.app.model.fileBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.LibArchiveUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Collections
import java.util.Enumeration
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

class CbzFile(var book: Book) {

    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif")

    private interface ZipFileWrapper {
        fun getEntry(name: String): CbzEntry?
        fun getInputStream(entry: CbzEntry): InputStream?
        fun entries(): Enumeration<out CbzEntry>
        fun close()
    }

    data class CbzEntry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long = -1,
        val compressedSize: Long = -1,
        val method: Int = -1,
        val time: Long = -1
    )

    data class CbzImageEntry(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val method: Int,
        val entryOffset: Int = 0
    ) {
        fun toCbzEntry(): CbzEntry {
            return CbzEntry(name, false, size, compressedSize, method)
        }
    }

    data class CbzImageCache(
        val entries: List<CbzImageEntry>, val eocdOffset: Long = 0, val centralOffset: Long = 0
    )

    companion object : BaseLocalBookParse {
        private var eFile: CbzFile? = null

        @Synchronized
        private fun getEFile(book: Book) =
            (eFile?.takeIf { it.book.bookUrl == book.bookUrl } ?: run {
                eFile?.close()
                CbzFile(book).also { eFile = it }
            }).apply { this.book = book }

        override fun getChapterList(book: Book) = getEFile(book).getChapterList()

        override fun getContent(book: Book, chapter: BookChapter) =
            getEFile(book).getContent(chapter)

        @Synchronized
        override fun upBookInfo(book: Book) = getEFile(book).upBookInfo()

        override fun getImage(book: Book, href: String) =
            getEFile(book).zipFile?.run { getEntry(href)?.let { getInputStream(it) } }

        fun clear() {
            eFile?.close(); eFile = null
        }

        // ZIP 结构常量 (用于 RemoteZipFile 和 ContentZipWrapper)
        private object ZipConstants {
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
        }

        private fun readLeShort(b: ByteArray, off: Int): Int {
            return (b[off].toInt() and 0xff) or (b[off + 1].toInt() and 0xff shl 8)
        }

        private fun readLeInt(b: ByteArray, off: Int): Int {
            return readLeShort(b, off) or (readLeShort(b, off + 2) shl 16)
        }
    }

    @Volatile
    private var fileDescriptor: ParcelFileDescriptor? = null

    @Volatile
    private var zipFile: ZipFileWrapper? = null
        get() = field ?: synchronized(this) {
            field ?: runCatching {
                if (book.bookUrl.startsWith(BookType.webDavTag)) {
                    val url = book.getRemoteUrl() ?: throw WebDavException("Remote URL not found")
                    val webdav = runCatching { WebDav.fromPath(url) }.getOrElse {
                        AppWebDav.authorization?.let {
                            WebDav(url, it)
                        } ?: throw WebDavException("No Auth")
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
                    if (size <= 0) {
                        size = runBlocking { webdav.getWebDavFile()?.size } ?: 0L
                    }
                    val count = book.wordCount?.replace("页", "")?.toIntOrNull() ?: 0
                    if (eocd > 0 && central > 0 && count > 0) return@runCatching RemoteZipFile(
                        webdav, book.originName, size, eocd, central, count
                    )
                    RemoteZipFile(webdav, book.originName, size)
                } else if (book.bookUrl.lowercase().endsWith(".cbz") || book.bookUrl.lowercase()
                        .endsWith(".zip")
                ) {
                    val uri = book.getLocalUri()
                    if (uri.isContentScheme()) {
                        fileDescriptor = BookHelp.getBookPFD(book)
                        fileDescriptor?.let { ContentZipWrapper(it) }
                    } else {
                        LocalZipWrapper(File(uri.path!!))
                    }
                } else BookHelp.getBookPFD(book)?.let {
                    fileDescriptor = it
                    LocalArchiveWrapper(it)
                }
            }.onFailure { AppLog.put("读取Cbz文件失败\n${it.localizedMessage}", it) }.getOrNull()
                .also { field = it }
        }

    @Volatile
    private var imageEntries: List<CbzEntry>? = null
        get() = field ?: synchronized(this) {
            field ?: initImageEntries()?.also { field = it }
        }

    private var imageEntriesByChapter: Map<String, List<CbzEntry>>? = null

    private fun getChapters(): Map<String, List<CbzEntry>> {
        imageEntries
        return imageEntriesByChapter ?: emptyMap()
    }

    private fun initImageEntries(): List<CbzEntry>? {
        val cacheFile = File(BookHelp.cachePath, "${book.getFolderName()}/cbz_images.json")
        val cache = runCatching {
            if (cacheFile.exists()) {
                cacheFile.inputStream().use {
                    GSON.fromJsonObject<CbzImageCache>(it).getOrNull()
                }
            } else null
        }.getOrNull()

        val res = if (cache != null) {
            (zipFile as? RemoteZipFile)?.restore(
                cache.eocdOffset,
                cache.centralOffset,
                cache.entries
            )
            cache.entries.map { it.toCbzEntry() }
        } else {
            zipFile?.run {
                runCatching {
                    entries().asSequence().filter {
                        !it.isDirectory && it.name.substringAfterLast(".").lowercase() in imageExts
                    }.sortedWith(compareBy(AlphanumComparator) { it.name }).toList()
                }.onFailure { AppLog.put("读取Cbz图片列表失败\n${it.localizedMessage}", it) }
                    .getOrNull()
            }?.also { entries ->
                saveCache(cacheFile, entries)
            }
        }

        return res?.also {
            imageEntriesByChapter =
                it.groupBy { entry -> entry.name.replace("\\", "/").substringBeforeLast("/", "") }
        }
    }

    private fun saveCache(cacheFile: File, entries: List<CbzEntry>) {
        val rzf = zipFile as? RemoteZipFile
        val newCache = CbzImageCache(
            entries = entries.map {
                CbzImageEntry(
                    it.name,
                    it.size,
                    it.compressedSize,
                    it.method,
                    rzf?.getEntryOffset(it.name) ?: 0
                )
            },
            eocdOffset = rzf?.eocdOffset ?: 0,
            centralOffset = rzf?.centralOffset ?: 0
        )
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.bufferedWriter().use {
                GSON.toJson(newCache, it)
            }
        }
    }

    private fun getZipCharset(): Charset {
        val charsetName = book.charset ?: imageEntries?.asSequence()
            ?.map { it.name }
            ?.filter { it.any { c -> c.code > 127 } }
            ?.take(5)?.joinToString("")
            ?.takeIf { it.isNotEmpty() }
            ?.let { EncodingDetect.getEncode(it.toByteArray(Charsets.ISO_8859_1)) }

        return charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
    }

    fun close() {
        zipFile?.close()
        fileDescriptor?.close()
        zipFile = null
        fileDescriptor = null
        imageEntries = null
        imageEntriesByChapter = null
    }

    private fun upBookInfo() {
        val zf = zipFile ?: run { eFile = null; book.intro = "书籍导入异常"; return }
        if (book.coverUrl.isNullOrEmpty()) book.coverUrl = FileBook.getCoverPath(book.bookUrl)
        if (book.name.isBlank()) book.name = book.originName.substringBeforeLast(".")
        if (book.charset.isNullOrEmpty()) book.charset = getZipCharset().name()

        (zf as? RemoteZipFile)?.apply {
            preload()
            book.variable = "cbz:$eocdOffset,$centralOffset,$fileSize"
        }
        book.wordCount = "${imageEntries?.size ?: 0}页"
        extractCover(zf)
        parseComicInfo(zf)
    }

    private fun extractCover(zf: ZipFileWrapper) {
        val coverUrl = book.coverUrl ?: return
        if (File(coverUrl).exists()) return
        imageEntries?.firstOrNull()?.let { entry ->
            runCatching {
                zf.getInputStream(entry)?.use { input ->
                    BitmapFactory.decodeStream(input)?.let { cover ->
                        FileOutputStream(FileUtils.createFileIfNotExist(coverUrl)).use {
                            cover.compress(Bitmap.CompressFormat.JPEG, 90, it)
                        }
                        cover.recycle()
                    }
                }
            }
        }
    }

    private fun parseComicInfo(zf: ZipFileWrapper) {
        zf.getEntry("ComicInfo.xml")?.let { entry ->
            runCatching {
                zf.getInputStream(entry)?.use { input ->
                    val doc = Jsoup.parse(input, "UTF-8", "", Parser.xmlParser())
                    doc.selectFirst("Title")?.text()?.takeUnless { it.isBlank() }
                        ?.let { book.name = it }
                    doc.selectFirst("Writer")?.text()?.takeUnless { it.isBlank() }
                        ?.let { book.author = it }
                    doc.selectFirst("Summary")?.text()?.takeUnless { it.isBlank() }
                        ?.let { book.intro = it }
                }
            }
        }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapters = getChapters()
        if (chapters.isEmpty()) return arrayListOf()
        val charset = getZipCharset()
        book.totalChapterNum = chapters.size

        val keys = chapters.keys.sortedWith(AlphanumComparator)
        val commonPrefix = keys.firstOrNull()?.substringBeforeLast("/", "")
            ?.takeIf { prefix -> prefix.isNotEmpty() && keys.all { it.startsWith("$prefix/") } }
            ?.let { "$it/" } ?: ""

        return keys.mapIndexedTo(ArrayList()) { i, f ->
            val displayTitle = if (commonPrefix.isNotEmpty() && f.startsWith(commonPrefix)) {
                f.substring(commonPrefix.length)
            } else f

            BookChapter(
                url = f,
                title = if (displayTitle.isNotEmpty()) try {
                    String(displayTitle.toByteArray(Charsets.ISO_8859_1), charset)
                } catch (_: Exception) {
                    displayTitle
                } else "正文",
                bookUrl = book.bookUrl,
                index = i
            )
        }
    }

    private fun getContent(chapter: BookChapter): String? {
        return getChapters()[chapter.url]?.joinToString("") { "<img src=\"${it.name}\"/>" }
    }

    protected fun finalize() {
        close()
    }

    // 使用 java.util.zip.ZipFile 处理本地 ZIP，支持指定编码且性能最好
    private class LocalZipWrapper(val file: File) : ZipFileWrapper {
        private var zipFile: ZipFile? = null

        private fun getZipFile(): ZipFile {
            return zipFile ?: ZipFile(file, Charsets.ISO_8859_1).also { zipFile = it }
        }

        override fun getEntry(name: String): CbzEntry? {
            return try {
                getZipFile().getEntry(name)?.let {
                    CbzEntry(
                        it.name,
                        it.isDirectory,
                        it.size,
                        it.compressedSize,
                        it.method,
                        it.time
                    )
                }
            } catch (e: Exception) {
                Log.e(javaClass.name, "getEntry error", e)
                null
            }
        }

        override fun getInputStream(entry: CbzEntry): InputStream? {
            return try {
                getZipFile().let { zf ->
                    zf.getEntry(entry.name)?.let { zEntry ->
                        zf.getInputStream(zEntry)
                    }
                }
            } catch (e: Exception) {
                Log.e(javaClass.name, "getInputStream error", e)
                null
            }
        }

        override fun entries(): Enumeration<out CbzEntry> {
            return try {
                val entries = getZipFile().entries()
                Collections.enumeration(entries.asSequence().map {
                    CbzEntry(
                        it.name,
                        it.isDirectory,
                        it.size,
                        it.compressedSize,
                        it.method,
                        it.time
                    )
                }.toList())
            } catch (e: Exception) {
                Log.e(javaClass.name, "entries error", e)
                Collections.enumeration(emptyList())
            }
        }

        override fun close() {
            zipFile?.close()
            zipFile = null
        }
    }

    // 使用 ParcelFileDescriptor 和 Os.lseek 实现随机访问，支持 content URI
    private class ContentZipWrapper(private val pfd: ParcelFileDescriptor) : ZipFileWrapper {
        private var entriesMap: HashMap<String, CbzEntry>? = null

        private fun seek(pos: Long) {
            try {
                Os.lseek(pfd.fileDescriptor, pos, OsConstants.SEEK_SET)
            } catch (e: ErrnoException) {
                throw IOException(e)
            }
        }

        private fun currentPosition(): Long {
            return try {
                Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR)
            } catch (e: ErrnoException) {
                throw IOException(e)
            }
        }

        private fun length(): Long {
            return try {
                Os.fstat(pfd.fileDescriptor).st_size
            } catch (e: ErrnoException) {
                throw IOException(e)
            }
        }

        private fun readFully(b: ByteArray, off: Int = 0, len: Int = b.size) {
            var n = 0
            while (n < len) {
                val count = try {
                    Os.read(pfd.fileDescriptor, b, off + n, len - n)
                } catch (e: ErrnoException) {
                    throw IOException(e)
                }
                if (count < 0) throw EOFException()
                n += count
            }
        }

        @Synchronized
        private fun readEntries(): HashMap<String, CbzEntry> {
            entriesMap?.let { return it }

            val fileLen = length()
            val sSize = minOf(fileLen, 65536L + ZipConstants.ENDHDR).toInt()
            val data = ByteArray(sSize)
            val sPos = fileLen - sSize
            seek(sPos)
            readFully(data)
            val pos = (data.size - ZipConstants.ENDHDR downTo 0).firstOrNull {
                readLeInt(data, it) == ZipConstants.ENDSIG.toInt()
            } ?: throw ZipException("central directory not found")

            val count = readLeShort(data, pos + ZipConstants.ENDTOT)
            val centralOffset = readLeInt(data, pos + ZipConstants.ENDOFF).toLong() and 0xffffffffL

            val entries = HashMap<String, CbzEntry>(count + count / 2)
            seek(centralOffset)
            val ebs = ByteArray(ZipConstants.CENHDR)

            repeat(count) {
                readFully(ebs)
                if (readLeInt(ebs, 0) != ZipConstants.CENSIG.toInt())
                    throw ZipException("Wrong Central Directory signature")

                val method = readLeShort(ebs, ZipConstants.CENHOW)
                val dostime = readLeInt(ebs, ZipConstants.CENTIM)
                val csize = readLeInt(ebs, ZipConstants.CENSIZ)
                val size = readLeInt(ebs, ZipConstants.CENLEN)
                val nameLen = readLeShort(ebs, ZipConstants.CENNAM)
                val extraLen = readLeShort(ebs, ZipConstants.CENEXT)
                val commentLen = readLeShort(ebs, ZipConstants.CENCOM)
                val offset = readLeInt(ebs, ZipConstants.CENOFF)

                val nameBytes = ByteArray(nameLen)
                readFully(nameBytes)
                val name = String(nameBytes, Charsets.ISO_8859_1)

                // Skip extra and comment
                val skipLen = extraLen + commentLen
                if (skipLen > 0) {
                    seek(currentPosition() + skipLen)
                }

                entries[name] = CbzEntry(
                    name = name,
                    isDirectory = false,
                    size = size.toLong() and 0xffffffffL,
                    compressedSize = csize.toLong() and 0xffffffffL,
                    method = method,
                    time = dostime.toLong()
                ).also {
                    // Store offset for later use
                    entryOffsets[name] = offset
                }
            }

            entriesMap = entries
            return entries
        }

        private val entryOffsets = HashMap<String, Int>()

        override fun getEntry(name: String): CbzEntry? {
            return try {
                readEntries()[name]
            } catch (_: Exception) {
                null
            }
        }

        override fun getInputStream(entry: CbzEntry): InputStream? {
            return try {
                val offset = entryOffsets[entry.name] ?: return null
                synchronized(pfd) {
                    val entryOffset = offset.toLong() and 0xffffffffL
                    seek(entryOffset)
                    val locBuf = ByteArray(ZipConstants.LOCHDR)
                    readFully(locBuf)

                    if (readLeInt(locBuf, 0) != ZipConstants.LOCSIG.toInt())
                        throw ZipException("Wrong Local header signature")

                    val nameLen = readLeShort(locBuf, ZipConstants.LOCNAM)
                    val extraLen = readLeShort(locBuf, ZipConstants.LOCEXT)
                    val dataOffset = entryOffset + ZipConstants.LOCHDR + nameLen + extraLen

                    val inputStream =
                        PartialInputStream(pfd, dataOffset, dataOffset + entry.compressedSize)
                    when (entry.method) {
                        0 -> inputStream // STORED
                        8 -> InflaterInputStream(inputStream, Inflater(true)) // DEFLATED
                        else -> throw ZipException("Unknown compression method ${entry.method}")
                    }
                }
            } catch (e: Exception) {
                AppLog.put("ContentZipWrapper getInputStream Error: ${e.localizedMessage}", e)
                null
            }
        }

        override fun entries(): Enumeration<out CbzEntry> {
            return try {
                Collections.enumeration(readEntries().values)
            } catch (_: Exception) {
                Collections.enumeration(emptyList())
            }
        }

        override fun close() {
            // pfd 由外部管理
        }

        private class PartialInputStream(
            private val pfd: ParcelFileDescriptor,
            private var filepos: Long,
            private val end: Long
        ) : InputStream() {
            override fun available(): Int = minOf((end - filepos).toInt(), Int.MAX_VALUE)

            override fun read(): Int {
                if (filepos >= end) return -1
                return synchronized(pfd) {
                    try {
                        Os.lseek(pfd.fileDescriptor, filepos++, OsConstants.SEEK_SET)
                        val b = ByteArray(1)
                        val count = Os.read(pfd.fileDescriptor, b, 0, 1)
                        if (count < 0) -1 else b[0].toInt() and 0xff
                    } catch (e: ErrnoException) {
                        throw IOException(e)
                    }
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (filepos >= end) return -1
                var length = len
                if (length > end - filepos) {
                    length = (end - filepos).toInt()
                }
                return synchronized(pfd) {
                    try {
                        Os.lseek(pfd.fileDescriptor, filepos, OsConstants.SEEK_SET)
                        val count = Os.read(pfd.fileDescriptor, b, off, length)
                        if (count > 0) filepos += count
                        count
                    } catch (e: ErrnoException) {
                        throw IOException(e)
                    }
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

    // 使用项目内置 LibArchiveUtils 实现本地文件访问，支持多种压缩格式 (RAR/7Z)
    private class LocalArchiveWrapper(val pfd: ParcelFileDescriptor) : ZipFileWrapper {
        private var names: List<String>? = null

        override fun getEntry(name: String): CbzEntry? {
            if (names == null) entries()
            return if (names?.contains(name) == true) CbzEntry(name, false) else null
        }

        override fun getInputStream(entry: CbzEntry): InputStream? {
            return try {
                val dupPfd = pfd.dup()
                Os.lseek(dupPfd.fileDescriptor, 0, OsConstants.SEEK_SET)
                val data = ParcelFileDescriptor.AutoCloseInputStream(dupPfd).use {
                    LibArchiveUtils.getByteArrayContent(it, entry.name)
                }
                data?.let { ByteArrayInputStream(it) }
            } catch (e: Exception) {
                AppLog.put("LocalArchiveWrapper getInputStream Error: ${e.localizedMessage}", e)
                null
            }
        }

        override fun entries(): Enumeration<out CbzEntry> {
            val list = LibArchiveUtils.getFilesName(pfd, null)
            names = list
            val cbzEntries = list.map { CbzEntry(it, false) }
            return Collections.enumeration(cbzEntries)
        }

        override fun close() {
        }
    }

    internal class RemoteZipFile(
        private val webDav: WebDav, private val name: String, val fileSize: Long
    ) : ZipFileWrapper {
        internal data class EntryMetadata(
            val entry: CbzEntry, val entryOffset: Int, var dataOffset: Long? = null
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
                val data =
                    webDav.readRange(if (fileSize > 0) fileSize - sSize else 0L, sSize, fileSize)
                val pos = (data.size - ZipConstants.ENDHDR downTo 0).firstOrNull {
                    readLeInt(data, it) == ZipConstants.ENDSIG.toInt()
                } ?: throw IOException("No EOCD: $name")
                eocdOffset = (if (fileSize > 0) fileSize - sSize else 0L) + pos
                entryCount = readLeShort(data, pos + ZipConstants.ENDTOT)
                centralOffset = readLeInt(data, pos + ZipConstants.ENDOFF).toLong() and 0xffffffffL
            }
            val dir =
                webDav.readRange(centralOffset, (eocdOffset - centralOffset).toInt(), fileSize)
            var p = 0
            return HashMap<String, EntryMetadata>(entryCount * 2).also { map ->
                repeat(entryCount) {
                    if (readLeInt(dir, p) != ZipConstants.CENSIG.toInt())
                        throw IOException("Wrong Central Sig")
                    val nLen = readLeShort(dir, p + ZipConstants.CENNAM)
                    val eLen = readLeShort(dir, p + ZipConstants.CENEXT)
                    val cLen = readLeShort(dir, p + ZipConstants.CENCOM)
                    // 使用 ISO_8859_1 保存原始字节，防止 UTF-8 解码损坏非 UTF-8 字符
                    val entryName = String(dir, p + ZipConstants.CENHDR, nLen, Charsets.ISO_8859_1)
                    map[entryName] = EntryMetadata(
                        CbzEntry(
                            name = entryName,
                            isDirectory = false,
                            method = readLeShort(dir, p + ZipConstants.CENHOW),
                            size = readLeInt(dir, p + ZipConstants.CENLEN).toLong() and 0xffffffffL,
                            compressedSize = readLeInt(
                                dir,
                                p + ZipConstants.CENSIZ
                            ).toLong() and 0xffffffffL,
                            time = readLeInt(dir, p + ZipConstants.CENTIM).toLong()
                        ), readLeInt(dir, p + ZipConstants.CENOFF)
                    )
                    p += ZipConstants.CENHDR + nLen + eLen + cLen
                }
                entriesMetadata = map
            }
        }

        override fun getEntry(name: String) = getMeta()[name]?.entry
        fun getEntryOffset(name: String) = getMeta()[name]?.entryOffset
        fun preload() {
            getMeta()
        }

        fun restore(eocd: Long, central: Long, es: List<CbzImageEntry>) {
            eocdOffset = eocd; centralOffset = central
            if (entriesMetadata == null) {
                entriesMetadata = HashMap<String, EntryMetadata>(es.size * 2).apply {
                    es.forEach { e ->
                        put(e.name, EntryMetadata(e.toCbzEntry(), e.entryOffset))
                    }
                }
                entryCount = es.size
            }
        }

        override fun entries() = Collections.enumeration(getMeta().values.map { it.entry })

        override fun close() {
            closed = true; entriesMetadata = null
        }

        override fun getInputStream(entry: CbzEntry): InputStream? {
            val m = getMeta()[entry.name] ?: throw NoSuchElementException(entry.name)
            val cSize = m.entry.compressedSize.takeIf { it <= Int.MAX_VALUE }?.toInt()
                ?: throw IOException("Too large")

            val bis = m.dataOffset?.let { dOff ->
                ByteArrayInputStream(webDav.readRange(dOff, cSize, fileSize))
            } ?: run {
                val entryOffset = m.entryOffset.toLong() and 0xffffffffL
                val totalToRead = ZipConstants.LOCHDR + entry.name.length + 128 + cSize
                val fullData = webDav.readRange(entryOffset, totalToRead, fileSize)

                if (fullData.size < ZipConstants.LOCHDR) throw IOException("Read Header Error")

                val realExtraLen = readLeShort(fullData, ZipConstants.LOCEXT)
                val realDataOff = ZipConstants.LOCHDR + entry.name.length + realExtraLen
                m.dataOffset = entryOffset + realDataOff

                val dataInBuffer = fullData.size - realDataOff
                if (dataInBuffer >= cSize) {
                    ByteArrayInputStream(fullData, realDataOff, cSize)
                } else {
                    val missing = cSize - maxOf(0, dataInBuffer)
                    val rest =
                        webDav.readRange(entryOffset + fullData.size, missing, fileSize)
                    val combined = ByteArray(cSize)
                    if (dataInBuffer > 0) {
                        System.arraycopy(fullData, realDataOff, combined, 0, dataInBuffer)
                    }
                    System.arraycopy(rest, 0, combined, maxOf(0, dataInBuffer), rest.size)
                    ByteArrayInputStream(combined)
                }
            }

            return if (m.entry.method == 8) { // DEFLATED
                InflaterInputStream(bis, Inflater(true))
            } else bis
        }
    }
}
