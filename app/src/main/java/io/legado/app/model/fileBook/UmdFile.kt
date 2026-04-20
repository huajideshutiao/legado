package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.LogUtils
import io.legado.app.utils.printOnDebug
import me.ag2s.umdlib.domain.UmdBook
import me.ag2s.umdlib.umd.UmdReader
import java.io.File
import java.io.InputStream

class UmdFile(var book: Book) {
    companion object : BaseFileBook {
        private var uFile: UmdFile? = null

        @Synchronized
        private fun getUFile(book: Book): UmdFile {
            if (uFile == null || uFile?.book?.bookUrl != book.bookUrl) {
                uFile = UmdFile(book)
                return uFile!!
            }
            uFile?.book = book
            return uFile!!
        }

        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getUFile(book).getChapterList()
        }

        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getUFile(book).getContent(chapter)
        }

        override fun getImage(
            book: Book,
            href: String
        ): InputStream? {
            return getUFile(book).getImage()
        }


        @Synchronized
        override fun upBookInfo(book: Book) {
            return getUFile(book).upBookInfo()
        }

        override fun clear() {
            uFile = null
        }
    }


    @Volatile
    private var umdBook: UmdBook? = null
        get() = field ?: synchronized(this) {
            field ?: readUmd().also { field = it }
        }

    init {
        upBookCover(true)
    }

    private fun readUmd(): UmdBook? {
        kotlin.runCatching {
            val input = FileBook.getBookInputStream(book)
            return UmdReader().read(input)
        }.onFailure {
            it.printOnDebug()
        }
        return null
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        try {
            umdBook?.let {
                if (book.coverUrl.isNullOrEmpty()) {
                    book.coverUrl = FileBook.getCoverPath(book.bookUrl)
                }
                if (fastCheck && File(book.coverUrl!!).exists()) {
                    return
                }
                FileUtils.writeBytes(book.coverUrl!!, it.cover.coverData)
            }
        } catch (e: Exception) {
            e.printOnDebug()
        }
    }

    private fun upBookInfo() {
        if (umdBook == null) {
            uFile = null
            book.intro = "书籍导入异常"
        } else {
            upBookCover()
            val hd = umdBook!!.header
            book.name = hd.title
            book.author = hd.author
            book.kind = hd.bookType
        }
    }

    private fun getContent(chapter: BookChapter): String? {
        return umdBook?.chapters?.getContentString(chapter.index)
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        umdBook?.chapters?.titles?.forEachIndexed { index, _ ->
            val title = umdBook!!.chapters.getTitle(index)
            val chapter = BookChapter()
            chapter.title = title
            chapter.index = index
            chapter.bookUrl = book.bookUrl
            chapter.url = index.toString()
            LogUtils.d(javaClass.name, chapter.url)
            chapterList.add(chapter)
        }
        return chapterList
    }

    private fun getImage(): InputStream? {
        return null
    }

}
