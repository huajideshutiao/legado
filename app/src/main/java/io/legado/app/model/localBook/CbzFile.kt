package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.printOnDebug
import me.ag2s.epublib.util.zip.AndroidZipEntry
import me.ag2s.epublib.util.zip.AndroidZipFile
import me.ag2s.epublib.util.zip.RemoteZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class CbzFile(var book: Book) {

    private interface ZipFileWrapper {
        fun getEntry(name: String): AndroidZipEntry?
        fun getInputStream(entry: AndroidZipEntry): InputStream
        fun entries(): java.util.Enumeration<out AndroidZipEntry>
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
                    val enumeration = zf.entries()
                    while (enumeration.hasMoreElements()) {
                        val entry = enumeration.nextElement()
                        if (!entry.isDirectory) {
                            val name = entry.name.lowercase()
                            if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                                name.endsWith(".png") || name.endsWith(".webp") ||
                                name.endsWith(".gif")
                            ) {
                                entries.add(entry)
                            }
                        }
                    }
                    entries.sortWith(compareBy(AlphanumComparator) { it.name })
                }
                field = entries
            }
            return field
        }

    init {
        upBookCover(true)
    }

    private fun openZipFile(): ZipFileWrapper? {
        return kotlin.runCatching {
            BookHelp.getBookPFD(book)?.let { pfd ->
                fileDescriptor = pfd
                if (book.bookUrl.startsWith(BookType.webDavTag)) {
                    val rzf = RemoteZipFile(pfd, book.originName, pfd.statSize)
                    object : ZipFileWrapper {
                        override fun getEntry(name: String): AndroidZipEntry? = rzf.getEntry(name)
                        override fun getInputStream(entry: AndroidZipEntry): InputStream = rzf.getInputStream(entry)
                        override fun entries(): java.util.Enumeration<out AndroidZipEntry> = rzf.entries()
                        override fun close() = rzf.close()
                    }
                } else {
                    val azf = AndroidZipFile(pfd, book.originName)
                    object : ZipFileWrapper {
                        override fun getEntry(name: String): AndroidZipEntry? = azf.getEntry(name)
                        override fun getInputStream(entry: AndroidZipEntry): InputStream = azf.getInputStream(entry)
                        override fun entries(): java.util.Enumeration<out AndroidZipEntry> = azf.entries()
                        override fun close() = azf.close()
                    }
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

    private fun upBookCover(fastCheck: Boolean = false) {
        try {
            if (book.coverUrl.isNullOrEmpty()) {
                book.coverUrl = LocalBook.getCoverPath(book)
            }
            if (fastCheck && File(book.coverUrl!!).exists()) {
                return
            }
            imageEntries?.firstOrNull()?.let { entry ->
                zipFile?.getInputStream(entry)?.use { input ->
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
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
    }

    private fun upBookInfo() {
        if (zipFile == null) {
            eFile = null
            book.intro = "书籍导入异常"
        } else {
            upBookCover()
            val zf = zipFile!!

            var name = book.name
            var author = book.author
            var intro = book.intro

            kotlin.runCatching {
                zf.getEntry("ComicInfo.xml")?.let { comicInfoEntry ->
                    zf.getInputStream(comicInfoEntry).use { input ->
                        val doc = Jsoup.parse(input, "UTF-8", "", Parser.xmlParser())
                        doc.selectFirst("Title")?.text()?.takeIf { it.isNotBlank() }?.let { name = it }
                        doc.selectFirst("Writer")?.text()?.takeIf { it.isNotBlank() }?.let { author = it }
                        doc.selectFirst("Summary")?.text()?.takeIf { it.isNotBlank() }?.let { intro = it }
                    }
                }
            }

            if (name.isBlank()) {
                name = book.originName.substringBeforeLast(".")
            }

            book.name = name
            book.author = author
            book.intro = intro
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
                    bookUrl = book.bookUrl,
                    title = title,
                    index = index++,
                    url = folder
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
}
