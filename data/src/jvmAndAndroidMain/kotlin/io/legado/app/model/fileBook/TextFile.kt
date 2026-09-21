package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.EmptyFileException
import io.legado.app.help.book.isLocalModified
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * TXT 本地书籍解析 (jvmAndAndroid 薄壳)。
 *
 * 分章算法已逐行下沉 commonMain [TextFileCore] (iOS/鸿蒙共用), 本壳仅保留原对外签名
 * (companion : BaseFileBook 单例缓存 + @Synchronized + @Throws + 实例方法), app/desktop 消费方零改动。
 * jvm 端行为不变: core 内正则/Charset/runBlocking/GC 的 jvm actual 均为原 API 直通。
 */
class TextFile(book: Book) {

    companion object : BaseFileBook {
        private var textFile: TextFile? = null

        @Synchronized
        private fun getTextFile(book: Book): TextFile {
            if (textFile == null || textFile?.core?.book?.bookUrl != book.bookUrl || book.isLocalModified()) {
                textFile = TextFile(book)
                return textFile!!
            }
            textFile?.core?.book = book
            return textFile!!
        }

        override fun upBookInfo(book: Book) {}

        @Throws(FileNotFoundException::class)
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getTextFile(book).getChapterList()
        }

        @Synchronized
        @Throws(FileNotFoundException::class)
        override fun getContent(book: Book, chapter: BookChapter): String {
            return getTextFile(book).getContent(chapter)
        }

        override fun getImage(book: Book, href: String): InputStream? = null

        override fun clear() {
            textFile = null
        }

    }

    private val core = TextFileCore(book)

    /**
     * 获取目录
     */
    @Throws(FileNotFoundException::class, SecurityException::class, EmptyFileException::class)
    fun getChapterList(): ArrayList<BookChapter> = core.getChapterList()

    fun getContent(chapter: BookChapter): String = core.getContent(chapter)

}
