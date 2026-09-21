package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isLocalModified
import io.legado.app.utils.InputStream
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * TXT 本地书解析 (nativeMain 壳, iOS/鸿蒙共用, 命名对齐 EpubFile.native.kt 同名模式)。
 *
 * 分章算法复用 commonMain [TextFileCore]; 缓存/失效逻辑与 jvmAndAndroid TextFile companion 同构,
 * @Synchronized 无 native 等价, 以 SynchronizedObject 可重入锁替代 (项目既有约定)。
 */
object TextFile : BaseFileBook {

    private val lock = SynchronizedObject()
    private var core: TextFileCore? = null

    private fun getCore(book: Book): TextFileCore = synchronized(lock) {
        val cur = core
        if (cur == null || cur.book.bookUrl != book.bookUrl || book.isLocalModified()) {
            TextFileCore(book).also { core = it }
        } else {
            cur.book = book
            cur
        }
    }

    override fun upBookInfo(book: Book) {}

    override fun getChapterList(book: Book): ArrayList<BookChapter> {
        return getCore(book).getChapterList()
    }

    override fun getContent(book: Book, chapter: BookChapter): String {
        return synchronized(lock) { getCore(book).getContent(chapter) }
    }

    override fun getImage(book: Book, href: String): InputStream? = null

    override fun clear() {
        core = null
    }
}
