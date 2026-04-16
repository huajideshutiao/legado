package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.printOnDebug
import me.ag2s.epublib.util.zip.AndroidZipEntry
import me.ag2s.epublib.util.zip.AndroidZipFile
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class CbzFile(var book: Book) {

    companion object : BaseLocalBookParse {
        private var eFile: CbzFile? = null

        @Synchronized
        private fun getEFile(book: Book): CbzFile {
            if (eFile == null || eFile?.book?.bookUrl != book.bookUrl) {
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
            eFile = null
        }
    }

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var zipFile: AndroidZipFile? = null
        get() {
            if (field == null || fileDescriptor == null) {
                field = readZip()
            }
            return field
        }

    private var imageEntries: List<AndroidZipEntry>? = null

    init {
        upBookCover(true)
    }

    private fun readZip(): AndroidZipFile? {
        return kotlin.runCatching {
            BookHelp.getBookPFD(book)?.let {
                fileDescriptor = it
                val zf = AndroidZipFile(it, book.originName)

                val entries = mutableListOf<AndroidZipEntry>()
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
                imageEntries = entries
                zf
            }
        }.onFailure {
            AppLog.put("读取Cbz文件失败\n${it.localizedMessage}", it)
            it.printOnDebug()
        }.getOrNull()
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        val zf = zipFile ?: return
        val entries = imageEntries ?: return
        if (entries.isEmpty()) return

        try {
            if (book.coverUrl.isNullOrEmpty()) {
                book.coverUrl = LocalBook.getCoverPath(book)
            }
            if (fastCheck && File(book.coverUrl!!).exists()) {
                return
            }
            val coverEntry = entries.first()
            zf.getInputStream(coverEntry)?.use { input ->
                val cover = BitmapFactory.decodeStream(input)
                val out = FileOutputStream(FileUtils.createFileIfNotExist(book.coverUrl!!))
                cover.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
                out.close()
            } ?: AppLog.putDebug("Cbz: 封面获取为空. path: ${book.bookUrl}")
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
    }

    private fun upBookInfo() {
        val zf = zipFile ?: return
        val entries = imageEntries ?: return

        upBookCover()
        var name = book.name
        var author = book.author
        var intro = book.intro

        kotlin.runCatching {
            val comicInfoEntry = zf.getEntry("ComicInfo.xml")
            if (comicInfoEntry != null) {
                zf.getInputStream(comicInfoEntry).use { input ->
                    val doc = Jsoup.parse(input, "UTF-8", "", org.jsoup.parser.Parser.xmlParser())
                    doc.selectFirst("Title")?.text()?.takeIf { it.isNotBlank() }?.let { name = it }
                    doc.selectFirst("Writer")?.text()?.takeIf { it.isNotBlank() }
                        ?.let { author = it }
                    doc.selectFirst("Summary")?.text()?.takeIf { it.isNotBlank() }
                        ?.let { intro = it }
                }
            } else if (name.isBlank()) {
                name = book.originName.substringBeforeLast(".")
            }
        }

        book.name = name
        book.author = author
        book.intro = intro
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val list = ArrayList<BookChapter>()
        val zf = zipFile ?: return list
        val entries = imageEntries ?: return list

        // Group images by their folder path
        val grouped = entries.groupBy {
            val parent = it.name.substringBeforeLast("/", "")
            parent
        }

        var index = 0
        for ((folder, chapterEntries) in grouped) {
            val title = if (folder.isEmpty()) "正文" else folder.substringAfterLast("/")
            val chapter = BookChapter(
                bookUrl = book.bookUrl,
                title = title,
                index = index++,
                url = folder // use folder as chapter URL to identify it
            )
            list.add(chapter)
        }

        return list
    }

    private fun getContent(chapter: BookChapter): String? {
        val zf = zipFile ?: return null
        val entries = imageEntries ?: return null

        val chapterFolder = chapter.url
        val chapterImages = entries.filter {
            val parent = it.name.substringBeforeLast("/", "")
            parent == chapterFolder
        }

        val html = StringBuilder()
        for (img in chapterImages) {
            html.append("<img src=\"/${img.name}\" />\n")
        }

        return HtmlFormatter.formatKeepImg(html.toString())
    }

    private fun getImage(href: String): InputStream? {
        val zf = zipFile ?: return null
        return kotlin.runCatching {
            val entry = zf.getEntry(href.removePrefix("/"))
            if (entry != null) {
                zf.getInputStream(entry)
            } else null
        }.getOrNull()
    }
}
