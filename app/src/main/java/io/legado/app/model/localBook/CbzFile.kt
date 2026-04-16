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
                eFile?.close()
                eFile = CbzFile(book)
            } else {
                eFile?.book = book
            }
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

        @Synchronized
        fun clear() {
            eFile?.close()
            eFile = null
        }
    }

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var zipFile: AndroidZipFile? = null
    private var imageEntries: List<AndroidZipEntry>? = null

    /**
     * 初始化 Zip 文件，确保只加载一次
     */
    private fun initZipFile(): AndroidZipFile? {
        val currentZip = zipFile
        if (currentZip != null) return currentZip

        return synchronized(this) {
            if (zipFile != null) return zipFile

            kotlin.runCatching {
                BookHelp.getBookPFD(book)?.let { pfd ->
                    fileDescriptor = pfd
                    val zf = AndroidZipFile(pfd, book.originName)
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
                    zipFile = zf
                    zf
                }
            }.onFailure {
                AppLog.put("读取Cbz文件失败\n${it.localizedMessage}", it)
                it.printOnDebug()
                close() // 失败时立即清理句柄
            }.getOrNull()
        }
    }

    fun close() {
        // 使用局部变量防止在检查 null 时触发任何 Getter（虽然现在已经移除了 Getter）
        val zf = zipFile
        val pfd = fileDescriptor
        zipFile = null
        fileDescriptor = null
        imageEntries = null

        try {
            zf?.close()
            pfd?.close()
        } catch (e: Exception) {
            e.printOnDebug()
        }
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        if (fastCheck && !book.coverUrl.isNullOrEmpty() && File(book.coverUrl!!).exists()) {
            return
        }

        val zf = initZipFile() ?: return
        val entries = imageEntries ?: return
        if (entries.isEmpty()) return

        try {
            if (book.coverUrl.isNullOrEmpty()) {
                book.coverUrl = LocalBook.getCoverPath(book)
            }
            val coverEntry = entries.first()
            zf.getInputStream(coverEntry)?.use { input ->
                val cover = BitmapFactory.decodeStream(input)
                if (cover != null) {
                    val out = FileOutputStream(FileUtils.createFileIfNotExist(book.coverUrl!!))
                    cover.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                    out.close()
                    cover.recycle()
                }
            }
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
    }

    private fun upBookInfo() {
        upBookCover(true)
        val zf = initZipFile() ?: return
        
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
            }
        }

        if (name.isBlank()) {
            name = book.originName.substringBeforeLast(".")
        }

        book.name = name
        book.author = author
        book.intro = intro
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val list = ArrayList<BookChapter>()
        initZipFile() // 确保数据已加载
        val entries = imageEntries ?: return list

        // Group images by their folder path
        val grouped = entries.groupBy {
            it.name.substringBeforeLast("/", "")
        }

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

        return list
    }

    private fun getContent(chapter: BookChapter): String? {
        initZipFile() ?: return null
        val entries = imageEntries ?: return null

        val chapterFolder = chapter.url
        val chapterImages = entries.filter {
            it.name.substringBeforeLast("/", "") == chapterFolder
        }

        val html = StringBuilder()
        for (img in chapterImages) {
            html.append("<img src=\"/${img.name}\" />\n")
        }

        return HtmlFormatter.formatKeepImg(html.toString())
    }

    private fun getImage(href: String): InputStream? {
        val zf = initZipFile() ?: return null
        return kotlin.runCatching {
            val entry = zf.getEntry(href.removePrefix("/"))
            if (entry != null) {
                zf.getInputStream(entry)
            } else null
        }.getOrNull()
    }
}
