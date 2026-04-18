package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.runBlocking
import me.ag2s.epublib.util.zip.AndroidZipEntry
import me.ag2s.epublib.util.zip.AndroidZipFile
import me.ag2s.epublib.util.zip.ZipConstants
import me.ag2s.epublib.util.zip.ZipException
import me.ag2s.epublib.util.zip.readLeInt
import me.ag2s.epublib.util.zip.readLeShort
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Collections
import java.util.Enumeration
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipOutputStream

class CbzFile(var book: Book) {

    private interface ZipFileWrapper {
        fun getEntry(name: String): AndroidZipEntry?
        fun getInputStream(entry: AndroidZipEntry): InputStream
        fun entries(): Enumeration<out AndroidZipEntry>
        fun close()
    }

    data class CbzImageEntry(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val method: Int,
        val entryOffset: Int = 0
    )

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
                            WebDav(
                                url, it
                            )
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
                    val count = book.wordCount?.toIntOrNull() ?: 0
                    if (eocd > 0 && central > 0 && count > 0) return@runCatching RemoteZipFile(
                        webdav, book.originName, size, eocd, central, count
                    )
                    RemoteZipFile(webdav, book.originName, size)
                } else BookHelp.getBookPFD(book)
                    ?.let { fileDescriptor = it; AndroidZipFileWrapper(it, book.originName) }
            }.onFailure { AppLog.put("读取Cbz文件失败\n${it.localizedMessage}", it) }.getOrNull()
                .also { field = it }
        }

    @Volatile
    private var imageEntries: List<AndroidZipEntry>? = null
        get() = field ?: synchronized(this) {
            field ?: run {
                val cacheFile = File(BookHelp.cachePath, "${book.getFolderName()}/cbz_images.json")
                val cache = runCatching {
                    if (cacheFile.exists()) GSON.fromJsonObject<CbzImageCache>(cacheFile.readText())
                        .getOrNull() else null
                }.getOrNull()
                cache?.let {
                    (zipFile as? RemoteZipFile)?.restore(
                        it.eocdOffset, it.centralOffset, it.entries
                    )
                }
                val res = cache?.entries?.map { e ->
                    val nameBytes = e.name.toByteArray()
                    AndroidZipEntry(
                        e.name, nameBytes.size
                    ).apply { setSize(e.size); setCompressedSize(e.compressedSize); setMethod(e.method) }
                } ?: zipFile?.run {
                    runCatching {
                        val exts = setOf("jpg", "jpeg", "png", "webp", "gif")
                        entries().asSequence().filter {
                            !it.isDirectory && it.name.substringAfterLast(".").lowercase() in exts
                        }.sortedWith(compareBy(AlphanumComparator) { it.name }).toList()
                    }.onFailure { AppLog.put("读取Cbz图片列表失败\n${it.localizedMessage}", it) }
                        .getOrNull()
                }?.also { entries ->
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
                        cacheFile.parentFile?.mkdirs(); cacheFile.writeText(
                        GSON.toJson(
                            newCache
                        )
                    )
                    }
                }
                res?.also {
                    imageEntriesByChapter =
                        it.groupBy { entry -> entry.name.substringBeforeLast("/", "") }
                    field = it
                }
            }
        }

    private var imageEntriesByChapter: Map<String, List<AndroidZipEntry>>? = null

    fun close() {
        zipFile?.close(); fileDescriptor?.close(); zipFile = null; fileDescriptor =
            null; imageEntries = null; imageEntriesByChapter = null
    }

    private fun upBookInfo() {
        val zf = zipFile ?: run { eFile = null; book.intro = "书籍导入异常"; return }
        if (book.coverUrl.isNullOrEmpty()) book.coverUrl = LocalBook.getCoverPath(book)
        if (book.name.isBlank()) book.name = book.originName.substringBeforeLast(".")
        val rzf = zf as? RemoteZipFile
        rzf?.preload()
        if (rzf != null) {
            book.variable =
                "cbz:${rzf.eocdOffset},${rzf.centralOffset},${rzf.fileSize}"
        }
        book.wordCount = "${imageEntries?.size ?: 0}页"
        if (!File(book.coverUrl!!).exists()) imageEntries?.firstOrNull()?.let { entry ->
            runCatching {
                zf.getInputStream(entry).use { input ->
                    BitmapFactory.decodeStream(input)?.let { cover ->
                        FileOutputStream(FileUtils.createFileIfNotExist(book.coverUrl!!)).use {
                            cover.compress(
                                Bitmap.CompressFormat.JPEG, 90, it
                            )
                        }
                        cover.recycle()
                    }
                }
            }
        }
        zf.getEntry("ComicInfo.xml")?.let { entry ->
            runCatching {
                zf.getInputStream(entry).use { input ->
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
        val chapters = imageEntriesByChapter ?: imageEntries?.let { imageEntriesByChapter }
        ?: return arrayListOf()
        book.totalChapterNum = chapters.size
        return chapters.keys.sortedWith(AlphanumComparator).mapIndexedTo(ArrayList()) { i, f ->
            BookChapter(
                url = f,
                title = f.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: "正文",
                bookUrl = book.bookUrl,
                index = i)
            }
    }

    private fun getContent(chapter: BookChapter): String? {
        val chapters = imageEntriesByChapter ?: imageEntries?.let { imageEntriesByChapter }
        return chapters?.get(chapter.url)?.joinToString("") { "<img src=\"${it.name}\"/>" }
    }

    protected fun finalize() {
        close()
    }

    private class AndroidZipFileWrapper(pfd: ParcelFileDescriptor, name: String) : ZipFileWrapper {
        private val zf = AndroidZipFile(pfd, name)
        override fun getEntry(name: String): AndroidZipEntry? = zf.getEntry(name)
        override fun getInputStream(entry: AndroidZipEntry) = zf.getInputStream(entry)
        override fun entries() = zf.entries()
        override fun close() = zf.close()
    }

    internal class RemoteZipFile(
        private val webDav: WebDav, private val name: String, val fileSize: Long
    ) : ZipFileWrapper {
        internal data class EntryMetadata(
            val entry: AndroidZipEntry, val entryOffset: Int, var dataOffset: Long? = null
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
                    readLeInt(
                        data, it
                    ) == ZipConstants.ENDSIG
                } ?: throw ZipException("No EOCD: $name")
                eocdOffset = (if (fileSize > 0) fileSize - sSize else 0L) + pos
                entryCount = readLeShort(data, pos + ZipConstants.ENDTOT)
                centralOffset = readLeInt(data, pos + ZipConstants.ENDOFF).toLong()
            }
            val dir =
                webDav.readRange(centralOffset, (eocdOffset - centralOffset).toInt(), fileSize)
            var p = 0
            return HashMap<String, EntryMetadata>(entryCount * 2).also { map ->
                repeat(entryCount) {
                    if (readLeInt(
                            dir, p
                        ) != ZipConstants.CENSIG
                    ) throw ZipException("Wrong Central Sig")
                    val nLen = readLeShort(dir, p + ZipConstants.CENNAM)
                    val eLen = readLeShort(dir, p + ZipConstants.CENEXT)
                    val cLen = readLeShort(dir, p + ZipConstants.CENCOM)
                    val entryName = String(dir, p + ZipConstants.CENHDR, nLen)
                    map[entryName] = EntryMetadata(AndroidZipEntry(entryName, nLen).apply {
                        setMethod(readLeShort(dir, p + ZipConstants.CENHOW)); setCrc(
                        readLeInt(
                            dir, p + ZipConstants.CENCRC
                        ).toLong() and 0xffffffffL
                    )
                        setSize(
                            readLeInt(
                                dir, p + ZipConstants.CENLEN
                            ).toLong() and 0xffffffffL
                        ); setCompressedSize(
                        readLeInt(
                            dir, p + ZipConstants.CENSIZ
                        ).toLong() and 0xffffffffL
                    )
                        setTime(readLeInt(dir, p + ZipConstants.CENTIM).toLong())
                        if (eLen > 0) setExtra(
                            dir.copyOfRange(
                                p + ZipConstants.CENHDR + nLen,
                                p + ZipConstants.CENHDR + nLen + eLen
                            )
                        )
                    }, readLeInt(dir, p + ZipConstants.CENOFF))
                    p += ZipConstants.CENHDR + nLen + eLen + cLen
                }
                entriesMetadata = map
            }
        }

        override fun getEntry(name: String) = getMeta()[name]?.entry?.clone() as? AndroidZipEntry
        fun getEntryOffset(name: String) = getMeta()[name]?.entryOffset
        fun preload() {
            getMeta()
        }

        fun restore(eocd: Long, central: Long, es: List<CbzImageEntry>) {
            eocdOffset = eocd; centralOffset = central
            if (entriesMetadata == null) {
                entriesMetadata = HashMap<String, EntryMetadata>(es.size * 2).apply {
                    es.forEach { e ->
                        val nameBytes = e.name.toByteArray()
                        put(
                            e.name, EntryMetadata(
                                AndroidZipEntry(
                                    e.name, nameBytes.size
                                ).apply {
                                    setSize(e.size); setCompressedSize(e.compressedSize); setMethod(
                                    e.method
                                )
                                }, e.entryOffset
                            )
                        )
                    }
                }
                entryCount = es.size
            }
        }

        override fun entries() =
            Collections.enumeration(getMeta().values.map { it.entry.clone() as AndroidZipEntry })

        override fun close() {
            closed = true; entriesMetadata = null
        }

        override fun getInputStream(entry: AndroidZipEntry): InputStream {
            val m = getMeta()[entry.name] ?: throw NoSuchElementException(entry.name)
            val cSize = m.entry.compressedSize.takeIf { it <= Int.MAX_VALUE }?.toInt()
                ?: throw ZipException("Too large")

            val bis = m.dataOffset?.let { dOff ->
                // 如果偏移已知，直接 1 RTT 读取完整数据
                ByteArrayInputStream(webDav.readRange(dOff, cSize, fileSize))
            } ?: run {
                // 偏移未知，执行乐观读取 (Dry Run)
                val extraLenGuess = m.entry.extra?.size ?: 0
                // 多读 128 字节冗余量，应对可能的 Local Header Extra Field 长度差异
                val totalToRead = ZipConstants.LOCHDR + entry.nameLen + extraLenGuess + cSize + 128
                val fullData = webDav.readRange(m.entryOffset.toLong(), totalToRead, fileSize)

                if (fullData.size < ZipConstants.LOCHDR) throw ZipException("Read Header Error")

                val realExtraLen = readLeShort(fullData, ZipConstants.LOCEXT)
                val realDataOff = ZipConstants.LOCHDR + entry.nameLen + realExtraLen
                m.dataOffset = m.entryOffset.toLong() + realDataOff

                val dataInBuffer = fullData.size - realDataOff
                if (dataInBuffer >= cSize) {
                    // 情况 A: 乐观命中，1 RTT 搞定
                    ByteArrayInputStream(fullData, realDataOff, cSize)
                } else {
                    // 情况 B: 没读全，补齐剩余部分 (第 2 个 RTT)
                    val missing = cSize - maxOf(0, dataInBuffer)
                    val rest =
                        webDav.readRange(m.entryOffset.toLong() + fullData.size, missing, fileSize)
                    val combined = ByteArray(cSize)
                    if (dataInBuffer > 0) {
                        System.arraycopy(fullData, realDataOff, combined, 0, dataInBuffer)
                    }
                    System.arraycopy(rest, 0, combined, maxOf(0, dataInBuffer), rest.size)
                    ByteArrayInputStream(combined)
                }
            }

            return if (m.entry.method == ZipOutputStream.DEFLATED) {
                InflaterInputStream(bis, Inflater(true))
            } else bis
        }
    }
}
