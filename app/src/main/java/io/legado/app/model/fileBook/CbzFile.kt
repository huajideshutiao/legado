package io.legado.app.model.fileBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

class CbzFile(var book: Book) {

    companion object : BaseFileBook {
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

        override fun clear() {
            eFile?.close(); eFile = null
        }
    }

    @Volatile
    private var fileDescriptor: ParcelFileDescriptor? = null

    @Volatile
    private var zipFile: ZipFileWrapper? = null
        get() = field ?: synchronized(this) {
            field ?: ZipFileWrapper.create(book)?.let { result ->
                fileDescriptor = result.fileDescriptor
                result.wrapper.also { field = it }
            }
        }

    @Volatile
    private var imageEntries: List<ZipEntry>? = null
        get() = field ?: synchronized(this) {
            field ?: initImageEntries()?.also { field = it }
        }

    private var imageEntriesByChapter: Map<String, List<ZipEntry>>? = null

    private fun getChapters(): Map<String, List<ZipEntry>> {
        imageEntries
        return imageEntriesByChapter ?: emptyMap()
    }

    private fun initImageEntries(): List<ZipEntry>? {
        val cacheFile = File(BookHelp.cachePath, "${book.getFolderName()}/cbz_images.json")
        val cache = runCatching {
            if (cacheFile.exists()) {
                cacheFile.inputStream().use {
                    GSON.fromJsonObject<ZipImageCache>(it).getOrNull()
                }
            } else null
        }.getOrNull()

        val res = if (cache != null) {
            (zipFile as? RemoteZipWrapper)?.restore(
                cache.eocdOffset, cache.centralOffset, cache.entries
            )
            cache.entries
        } else {

            zipFile?.run {
                runCatching {
                    entries().asSequence().filter {
                        !it.isDirectory && AppPattern.imgFileRegex.matches(it.name)
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

    private fun saveCache(cacheFile: File, entries: List<ZipEntry>) {
        val rzf = zipFile as? RemoteZipWrapper
        val newCache = ZipImageCache(
            entries = entries.map {
                it.copy(entryOffset = rzf?.getEntryOffset(it.name) ?: 0)
            }, eocdOffset = rzf?.eocdOffset ?: 0, centralOffset = rzf?.centralOffset ?: 0
        )
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.bufferedWriter().use {
                GSON.toJson(newCache, it)
            }
        }
    }

    private fun getZipCharset(): Charset {
        val charsetName = book.charset ?: imageEntries?.asSequence()?.map { it.name }
            ?.filter { it.any { c -> c.code > 127 } }?.take(5)?.joinToString("")
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

        (zf as? RemoteZipWrapper)?.apply {
            imageEntries
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
                url = f, title = if (displayTitle.isNotEmpty()) try {
                    String(displayTitle.toByteArray(Charsets.ISO_8859_1), charset)
                } catch (_: Exception) {
                    displayTitle
                } else "正文", bookUrl = book.bookUrl, index = i
            )
        }
    }

    private fun getContent(chapter: BookChapter): String? {
        return getChapters()[chapter.url]?.joinToString("") { "<img src=\"${it.name}\"/>" }
    }

    protected fun finalize() {
        close()
    }
}
