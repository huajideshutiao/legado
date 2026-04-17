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
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.printOnDebug
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
import java.io.IOException
import java.io.InputStream
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

    companion object : BaseLocalBookParse {
        private var eFile: CbzFile? = null

        @Synchronized
        private fun getEFile(book: Book): CbzFile {
            if (eFile == null || eFile?.book?.bookUrl != book.bookUrl) {
                eFile?.close()
                eFile = CbzFile(book)
                return eFile!!
            }
            eFile?.book = book
            return eFile!!
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getEFile(book).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getEFile(book).getContent(chapter)
        }

        @Synchronized
        override fun getImage(book: Book, href: String): InputStream? {
            return getEFile(book).getImage(href)
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            getEFile(book).upBookInfo()
        }

        fun clear() {
            eFile?.close()
            eFile = null
        }
    }

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var zipFile: ZipFileWrapper? = null
        get() {
            if (field == null) {
                field = openZipFile()
            }
            return field
        }
    private var imageEntries: List<AndroidZipEntry>? = null
        get() {
            if (field == null) {
                val entries = mutableListOf<AndroidZipEntry>()
                zipFile?.let { zf ->
                    try {
                        val enumeration = zf.entries()
                        while (enumeration.hasMoreElements()) {
                            val entry = enumeration.nextElement()
                            if (!entry.isDirectory) {
                                val name = entry.name.lowercase()
                                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(
                                        ".png"
                                    ) || name.endsWith(
                                        ".webp"
                                    ) || name.endsWith(".gif")
                                ) {
                                    entries.add(entry)
                                }
                            }
                        }
                        entries.sortWith(compareBy(AlphanumComparator) { it.name })
                    } catch (e: Exception) {
                        AppLog.put("读取Cbz图片列表失败\n${e.localizedMessage}", e)
                    }
                }
                field = entries
            }
            return field
        }

    private fun openZipFile(): ZipFileWrapper? {
        return kotlin.runCatching {
            if (book.bookUrl.startsWith(BookType.webDavTag)) {
                val webDavUrl = book.getRemoteUrl() ?: throw WebDavException("Remote URL not found")
                val webdav = kotlin.runCatching {
                    WebDav.fromPath(webDavUrl)
                }.getOrElse {
                    AppWebDav.authorization?.let { auth ->
                        WebDav(webDavUrl, auth)
                    } ?: throw WebDavException("Unexpected defaultBookWebDav")
                }
                val size = runBlocking { webdav.getWebDavFile()?.size } ?: 0L

                val cachedData = book.variable?.takeIf { it.startsWith("cbz:") }?.substring(4)
                if (cachedData != null) {
                    val parts = cachedData.split(",")
                    if (parts.size == 2) {
                        val eocdOff = parts[0].toLongOrNull() ?: 0L
                        val centralOff = parts[1].toLongOrNull() ?: 0L
                        val count = book.wordCount?.toIntOrNull() ?: 0
                        if (eocdOff > 0 && centralOff > 0 && count > 0) {
                            return@runCatching RemoteZipFile(
                                webdav,
                                book.originName,
                                size,
                                eocdOff,
                                centralOff,
                                count
                            )
                        }
                    }
                }

                RemoteZipFile(webdav, book.originName, size)
            } else {
                BookHelp.getBookPFD(book)?.let { pfd ->
                    fileDescriptor = pfd
                    AndroidZipFileWrapper(pfd, book.originName)
                }
            }
        }.onFailure {
            AppLog.put("读取Cbz文件失败\n${it.localizedMessage}", it)
            it.printOnDebug()
        }.getOrNull()
    }

    fun close() {
        zipFile?.close()
        fileDescriptor?.close()
        zipFile = null
        fileDescriptor = null
        imageEntries = null
    }

    private fun upBookInfo() {
        if (zipFile == null) {
            eFile = null
            book.intro = "书籍导入异常"
            return
        }

        val zf = zipFile!!

        if (book.coverUrl.isNullOrEmpty()) {
            book.coverUrl = LocalBook.getCoverPath(book)
        }

        if (!File(book.coverUrl!!).exists()) {
            imageEntries?.firstOrNull()?.let { entry ->
                zf.getInputStream(entry).use { input ->
                    val cover = BitmapFactory.decodeStream(input)
                    if (cover != null) {
                        val out = FileOutputStream(FileUtils.createFileIfNotExist(book.coverUrl!!))
                        cover.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        out.flush()
                        out.close()
                        cover.recycle()
                    }
                }
            }
        }

        var name = book.name
        var author = book.author
        var intro = book.intro

        kotlin.runCatching {
            zf.getEntry("ComicInfo.xml")?.let { comicInfoEntry ->
                zf.getInputStream(comicInfoEntry).use { input ->
                    val doc = Jsoup.parse(input, "UTF-8", "", Parser.xmlParser())
                    doc.selectFirst("Title")?.text()?.takeIf { it.isNotBlank() }
                        ?.let { name = it }
                    doc.selectFirst("Writer")?.text()?.takeIf { it.isNotBlank() }
                        ?.let { author = it }
                    doc.selectFirst("Summary")?.text()?.takeIf { it.isNotBlank() }
                        ?.let { intro = it }
                }
            }
        }

        if (name.isBlank()) {
            name = book.originName.substringBeforeLast(".")
        }

        book.name = name
        book.author = author
        book.intro = intro

        if (zf is RemoteZipFile) {
            zf.preloadAndCache(book)
        }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val list = ArrayList<BookChapter>()
        val entries = imageEntries ?: return list

        val grouped = entries.groupBy {
            val name = it.name
            if (name.contains("/")) name.substringBeforeLast("/") else ""
        }

        if (grouped.size <= 1) {
            val chapter = BookChapter(
                bookUrl = book.bookUrl,
                title = "正文",
                index = 0,
                url = grouped.keys.firstOrNull() ?: ""
            )
            list.add(chapter)
        } else {
            var index = 0
            val sortedKeys = grouped.keys.sortedWith(AlphanumComparator)
            for (folder in sortedKeys) {
                val title = if (folder.isEmpty()) "正文" else folder.substringAfterLast("/")
                val chapter = BookChapter(
                    bookUrl = book.bookUrl, title = title, index = index++, url = folder
                )
                list.add(chapter)
            }
        }

        return list
    }

    private fun getContent(chapter: BookChapter): String? {
        val entries = imageEntries ?: return null
        val chapterFolder = chapter.url
        val chapterImages = entries.filter {
            val name = it.name
            val folder = if (name.contains("/")) name.substringBeforeLast("/") else ""
            folder == chapterFolder
        }

        val html = StringBuilder()
        for (img in chapterImages) {
            html.append("<img src=\"${img.name}\" />\n")
        }

        return HtmlFormatter.formatKeepImg(html.toString())
    }

    private fun getImage(href: String): InputStream? {
        return zipFile?.let { zf ->
            zf.getEntry(href)?.let { entry ->
                zf.getInputStream(entry)
            }
        }
    }

    protected fun finalize() {
        close()
    }

    private class AndroidZipFileWrapper(
        pfd: ParcelFileDescriptor, name: String
    ) : ZipFileWrapper {

        private val zipFile = AndroidZipFile(pfd, name)

        override fun getEntry(name: String): AndroidZipEntry? = zipFile.getEntry(name)

        override fun getInputStream(entry: AndroidZipEntry): InputStream =
            zipFile.getInputStream(entry)

        override fun entries(): Enumeration<out AndroidZipEntry> = zipFile.entries()

        override fun close() = zipFile.close()
    }

    internal class RemoteZipFile(
        private val webDav: WebDav, private val name: String, private val fileSize: Long
    ) : ZipFileWrapper {

        private data class EntryMetadata(
            val entry: AndroidZipEntry, val entryOffset: Int, var dataOffset: Long? = null
        )

        private var entriesMetadata: HashMap<String, EntryMetadata>? = null
        private var closed = false
        private var eocdOffset: Long = 0
        private var centralOffset: Long = 0
        private var entryCount: Int = 0

        constructor(
            webDav: WebDav,
            name: String,
            fileSize: Long,
            cachedEocdOffset: Long,
            cachedCentralOffset: Long,
            cachedEntryCount: Int
        ) : this(webDav, name, fileSize) {
            eocdOffset = cachedEocdOffset
            centralOffset = cachedCentralOffset
            entryCount = cachedEntryCount
        }

        @Synchronized
        @Throws(IOException::class)
        private fun readEntries() {
            if (eocdOffset == 0L || centralOffset == 0L || entryCount == 0) {
                val searchSize = if (fileSize > 0) {
                    minOf(fileSize, 65536L + ZipConstants.ENDHDR).toInt()
                } else {
                    (65536L + ZipConstants.ENDHDR).toInt()
                }
                val searchStart = if (fileSize > 0) fileSize - searchSize else 0L
                val searchData = webDav.readRange(searchStart, searchSize, fileSize)

                var endSigPos = -1
                for (i in searchData.size - ZipConstants.ENDHDR downTo 0) {
                    if (readLeInt(searchData, i) == ZipConstants.ENDSIG) {
                        endSigPos = i
                        break
                    }
                }

                if (endSigPos < 0) {
                    throw ZipException("central directory not found, probably not a zip file: $name")
                }

                eocdOffset = searchStart + endSigPos
                entryCount = readLeShort(searchData, endSigPos + ZipConstants.ENDTOT)
                centralOffset = readLeInt(searchData, endSigPos + ZipConstants.ENDOFF).toLong()
            }

            entriesMetadata = HashMap(entryCount + entryCount / 2)

            val centralDir =
                webDav.readRange(centralOffset, (eocdOffset - centralOffset).toInt(), fileSize)
            var cenPos = 0

            repeat(entryCount) {
                if (cenPos + ZipConstants.CENHDR > centralDir.size) {
                    throw ZipException("Invalid central directory")
                }

                if (readLeInt(centralDir, cenPos) != ZipConstants.CENSIG) {
                    throw ZipException("Wrong Central Directory signature: $name")
                }

                val method = readLeShort(centralDir, cenPos + ZipConstants.CENHOW)
                val dostime = readLeInt(centralDir, cenPos + ZipConstants.CENTIM)
                val crc = readLeInt(centralDir, cenPos + ZipConstants.CENCRC)
                val csize = readLeInt(centralDir, cenPos + ZipConstants.CENSIZ)
                val size = readLeInt(centralDir, cenPos + ZipConstants.CENLEN)
                val nameLen = readLeShort(centralDir, cenPos + ZipConstants.CENNAM)
                val extraLen = readLeShort(centralDir, cenPos + ZipConstants.CENEXT)
                val commentLen = readLeShort(centralDir, cenPos + ZipConstants.CENCOM)
                val offset = readLeInt(centralDir, cenPos + ZipConstants.CENOFF)

                val entryStart = cenPos + ZipConstants.CENHDR
                if (entryStart + nameLen > centralDir.size) {
                    throw ZipException("Invalid entry name")
                }

                val entryName = String(centralDir, entryStart, nameLen)

                val entry = AndroidZipEntry(entryName, nameLen)
                entry.setMethod(method)
                entry.setCrc(crc.toLong() and 0xffffffffL)
                entry.setSize(size.toLong() and 0xffffffffL)
                entry.setCompressedSize(csize.toLong() and 0xffffffffL)
                entry.setTime(dostime.toLong())

                if (extraLen > 0) {
                    val extraStart = entryStart + nameLen
                    if (extraStart + extraLen <= centralDir.size) {
                        val extra = ByteArray(extraLen)
                        System.arraycopy(centralDir, extraStart, extra, 0, extraLen)
                        entry.setExtra(extra)
                    }
                }

                if (commentLen > 0) {
                    val commentStart = entryStart + nameLen + extraLen
                    if (commentStart + commentLen <= centralDir.size) {
                        entry.setComment(String(centralDir, commentStart, commentLen))
                    }
                }

                entriesMetadata!![entryName] = EntryMetadata(entry, offset)

                cenPos += ZipConstants.CENHDR + nameLen + extraLen + commentLen
            }
        }

        @Throws(IOException::class)
        override fun close() {
            closed = true
            entriesMetadata = null
        }

        override fun entries(): Enumeration<out AndroidZipEntry> {
            return ZipEntryEnumeration(getEntriesMetadata().values.map { it.entry }.iterator())
        }

        @Synchronized
        @Throws(IOException::class)
        private fun getEntriesMetadata(): HashMap<String, EntryMetadata> {
            if (closed) {
                throw IllegalStateException("RemoteZipFile has closed: $name")
            }

            if (entriesMetadata == null) {
                readEntries()
            }

            return entriesMetadata!!
        }

        override fun getEntry(name: String): AndroidZipEntry? {
            return getEntriesMetadata()[name]?.entry?.clone() as? AndroidZipEntry
        }

        @Throws(IOException::class)
        private fun getDataOffset(entry: AndroidZipEntry): Long {
            val metadata = getEntriesMetadata()[entry.name]
                ?: throw ZipException("Entry not found: ${entry.name}")

            metadata.dataOffset?.let { return it }

            val entryOffset = metadata.entryOffset.toLong()
            val locBuf = webDav.readRange(entryOffset, ZipConstants.LOCHDR, fileSize)

            if (readLeInt(locBuf, 0) != ZipConstants.LOCSIG) {
                throw ZipException("Wrong Local header signature: $name")
            }

            if (entry.method != readLeShort(locBuf, ZipConstants.LOCHOW)) {
                throw ZipException("Compression method mismatch: $name")
            }

            if (entry.nameLen != readLeShort(locBuf, ZipConstants.LOCNAM)) {
                throw ZipException("file name length mismatch: $name")
            }

            val extraLen = entry.nameLen + readLeShort(locBuf, ZipConstants.LOCEXT)
            val dataOffset = entryOffset + ZipConstants.LOCHDR + extraLen
            metadata.dataOffset = dataOffset
            return dataOffset
        }

        @Throws(IOException::class)
        override fun getInputStream(entry: AndroidZipEntry): InputStream {
            val metadata =
                getEntriesMetadata()[entry.name] ?: throw NoSuchElementException(entry.name)

            val start = getDataOffset(metadata.entry)
            val compressedSize = metadata.entry.compressedSize
            if (compressedSize > Int.MAX_VALUE) {
                throw ZipException("Entry too large: ${entry.name} ($compressedSize bytes)")
            }
            val data = webDav.readRange(start, compressedSize.toInt(), fileSize)
            val `is`: InputStream = ByteArrayInputStream(data)
            return when (metadata.entry.method) {
                ZipOutputStream.STORED -> `is`
                ZipOutputStream.DEFLATED -> InflaterInputStream(`is`, Inflater(true))
                else -> throw ZipException("Unknown compression method ${metadata.entry.method}")
            }
        }

        private class ZipEntryEnumeration(
            private val elements: Iterator<AndroidZipEntry>
        ) : Enumeration<AndroidZipEntry> {
            override fun hasMoreElements() = elements.hasNext()
            override fun nextElement(): AndroidZipEntry = elements.next().clone() as AndroidZipEntry
        }

        fun preloadAndCache(book: Book) {
            getEntriesMetadata()
            book.variable = "cbz:$eocdOffset,$centralOffset"
            book.wordCount = entryCount.toString()
        }
    }
}
