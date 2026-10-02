package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.fileBook.BaseFileBook
import io.legado.app.model.fileBook.FileBookAccessor
import io.legado.app.model.fileBook.FileBookProviders
import io.legado.app.model.fileBook.TextFileCore
import io.legado.app.model.fileBook.completeTxtEncodingSample
import io.legado.app.utils.InputStream
import io.legado.app.utils.toInputStream
import java.lang.reflect.Proxy
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals

class LocalBookContentTest {
    @Test
    fun fixedSizeTxtDetectionSampleDoesNotEndInsideUtf8Character() {
        for (tail in listOf("文", "😀")) {
            val prefix = "阅读正文".repeat(30000).encodeToByteArray()
            val character = tail.encodeToByteArray()
            for (length in 1 until character.size) {
                assertContentEquals(prefix, completeTxtEncodingSample(prefix + character.copyOf(length)))
            }
        }
        val gbk = "阅读正文".repeat(100).toByteArray(Charset.forName("GBK"))
        assertContentEquals(gbk, completeTxtEncodingSample(gbk))
        val invalidInterior = byteArrayOf(0xff.toByte()) + "正文".encodeToByteArray()
        assertContentEquals(invalidInterior, completeTxtEncodingSample(invalidInterior))
    }

    @Test
    fun txtLargerThanDetectionBufferReadsChaptersAcrossBlockBoundary() {
        val firstBody = "第一段正文\n".repeat(25000)
        val secondBody = "第二段正文\n".repeat(25000)
        val bytes = ("第一章 开始\n" + firstBody + "第二章 继续\n" + secondBody).encodeToByteArray()
        val book = Book(bookUrl = "file:///books/large.txt", originName = "large.txt",
            type = BookType.local, charset = "UTF-8", tocUrl = "^第.*章.*$")
        book.config.splitLongChapter = false
        withProviders(null, book, bytes) { _, _ ->
            val chapters = FileBookProviders.get().getHandler(book).getChapterList(book)
            assertEquals(2, chapters.size)
            val first = BookHelpShared.getContent(book, chapters.first()).orEmpty()
            assertContains(first, "第一段正文")
            assertFalse(first.contains("第二段正文"))
            val second = BookHelpShared.getContent(book, chapters.last()).orEmpty()
            assertContains(second, "第二段正文")
            assertFalse(second.contains("第一段正文"))
            assertEquals(bytes.size.toLong(), chapters.last().end)
        }
    }

    @Test
    fun defaultBookHelpAccessorReadsUncachedLocalBookForExport() {
        val accessor = object : BookHelpAccessor {
            override fun saveContent(bookSource: BookSource, book: Book, bookChapter: BookChapter, content: String) = Unit
            override fun getCoverPath(bookUrl: String): String = error("Not used")
            override suspend fun clearCacheExtra() = Unit
        }
        withProviders(null) { reads, _ ->
            val book = Book(bookUrl = "file:///books/test.txt", originName = "test.txt", type = BookType.local)
            assertEquals("原文件正文", accessor.getContent(book, BookChapter(url = "chapter")))
            assertEquals(1, reads())
        }
    }

    @Test
    fun txtByteOffsetsProduceReadableChaptersWithoutCache() {
        val text = "第一章 开始\n第一段正文\n第二章 继续\n第二段正文\n"
        for (charset in listOf("UTF-8", "GB18030", "UTF-16LE", "UTF-16BE")) {
            val book = Book(bookUrl = "file:///books/test.txt", originName = "test.txt",
                type = BookType.local, charset = charset, tocUrl = "^第.*章.*$")
            withProviders(null, book, text.toByteArray(Charset.forName(charset))) { reads, _ ->
                val chapters = FileBookProviders.get().getHandler(book).getChapterList(book)
                assertEquals(2, chapters.size, charset)
                val first = BookHelpShared.getContent(book, chapters.first()).orEmpty()
                assertContains(first, "第一段正文", message = charset)
                assertFalse(first.contains("第二段正文"), charset)
                assertContains(BookHelpShared.getContent(book, chapters.last()).orEmpty(), "第二段正文", message = charset)
                assertEquals(2, reads())
            }
        }
    }

    @Test
    fun uncachedAndEmptyCachedLocalBooksReadOriginalFile() {
        for (extension in listOf("txt", "epub", "cbz")) {
            for (cache in listOf(null, "")) {
                withProviders(cache) { reads, writes ->
                    val book = Book(bookUrl = "file:///books/test.$extension", originName = "test.$extension", type = BookType.local)
                    assertEquals("原文件正文", BookHelpShared.getContent(book, BookChapter(url = "chapter")))
                    assertEquals(1, reads())
                    assertEquals(if (extension == "epub") listOf("原文件正文") else emptyList(), writes)
                }
            }
        }
    }

    @Test
    fun nonemptyCachePreservesChapterEdits() {
        withProviders("编辑后的正文") { reads, writes ->
            val book = Book(bookUrl = "file:///books/test.epub", originName = "test.epub", type = BookType.local)
            assertEquals("编辑后的正文", BookHelpShared.getContent(book, BookChapter(url = "chapter")))
            assertEquals(0, reads())
            assertEquals(emptyList(), writes)
        }
    }

    @Test
    fun onlineBookWithNoCacheDoesNotUseLocalParser() {
        withProviders(null) { reads, _ ->
            assertNull(BookHelpShared.getContent(Book(bookUrl = "https://example.org/book"), BookChapter(url = "chapter")))
            assertEquals(0, reads())
        }
    }

    private fun withProviders(cache: String?, textBook: Book? = null, textBytes: ByteArray? = null,
        block: (() -> Int, List<String>) -> Unit) {
        val previousStorage = runCatching { BookStorageProviders.get() }.getOrNull()
        val previousFiles = runCatching { FileBookProviders.get() }.getOrNull()
        var reads = 0
        val writes = mutableListOf<String>()
        val textCore = textBook?.let { TextFileCore(it) }
        val handler = object : BaseFileBook {
            override fun upBookInfo(book: Book) = Unit
            override fun getChapterList(book: Book) = textCore?.getChapterList() ?: arrayListOf<BookChapter>()
            override fun getContent(book: Book, chapter: BookChapter): String {
                reads++
                return textCore?.getContent(chapter) ?: "原文件正文"
            }
            override fun getImage(book: Book, href: String): InputStream? = null
        }
        BookStorageProviders.register(proxy { name, args ->
            when (name) {
                "readCacheFile" -> cache
                "saveText" -> { writes.add(args!![2] as String); Unit }
                else -> error("Unexpected storage call: $name")
            }
        })
        FileBookProviders.register(proxy { name, _ ->
            when (name) {
                "getHandler" -> handler
                "getBookInputStream" -> textBytes!!.toInputStream()
                "getLastModified" -> 0L // Result<Long> is unboxed on the JVM interface.
                else -> error("Unexpected file call: $name")
            }
        })
        try {
            block({ reads }, writes)
        } finally {
            BookStorageProviders::class.java.getDeclaredField("impl").apply {
                isAccessible = true
                set(null, previousStorage)
            }
            FileBookProviders::class.java.getDeclaredField("impl").apply {
                isAccessible = true
                set(null, previousFiles)
            }
        }
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name.substringBefore('-'), args)
        } as T
}
