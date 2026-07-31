package io.legado.app.ui.book.manage

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelpChapterLocator
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.LocalBookLocators

// 鸿蒙书架管理平台实现: 复用 commonMain 的 BookStorageProviders/LocalBookLocators/BookHelpChapterLocator
class OhosBookshelfManagePlatform : BookshelfManagePlatform {

    // 迁移旧书进度/分组/自定义字段到新书 (对照 app 端 Book.migrateTo)
    override fun migrateBook(oldBook: Book, newBook: Book, toc: List<BookChapter>): Book {
        val newIndex = BookHelpChapterLocator.getDurChapter(oldBook, toc)
        newBook.durChapterIndex = newIndex
        newBook.durChapterTitle = toc.getOrNull(newIndex)?.title ?: oldBook.durChapterTitle
        newBook.durChapterPos = oldBook.durChapterPos
        newBook.durChapterTime = oldBook.durChapterTime
        newBook.group = oldBook.group
        newBook.order = oldBook.order
        newBook.customCoverUrl = oldBook.customCoverUrl
        newBook.customIntro = oldBook.customIntro
        newBook.customTag = oldBook.customTag
        newBook.canUpdate = oldBook.canUpdate
        newBook.readConfig = oldBook.readConfig
        return newBook
    }

    // 清除书籍章节缓存: 委托 BookStorageProviders (OhosBookStorage 实现)
    override fun clearCache(book: Book) {
        BookStorageProviders.get().clearCache(book)
    }

    // 列出已缓存章节文件名集合: 委托 BookStorageProviders
    override fun getChapterFiles(book: Book): HashSet<String> {
        return BookStorageProviders.get().getChapterFiles(book).toHashSet()
    }

    // 删除本地书源文件: 委托 LocalBookLocators (OhosLocalBookLocator 实现)
    override fun deleteLocalBook(book: Book, deleteOriginal: Boolean) {
        if (deleteOriginal) {
            LocalBookLocators.get().deleteBook(book)
        }
    }

    // 鸿蒙无 R.string 资源系统, 硬编码文案 (与 desktop jvmGetString fallback 一致)
    override val clearCacheSuccessMessage: String = "清缓存成功"
}

// 鸿蒙宿主注册入口 (对照 app 端 registerAndroidBookshelfManagePlatform)
fun registerOhosBookshelfManagePlatform() {
    BookshelfManagePlatformProviders.register(OhosBookshelfManagePlatform())
}
