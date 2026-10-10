package io.legado.desktop.help.book

import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.manage.BookshelfManagePlatform
import io.legado.app.ui.book.manage.BookshelfManagePlatformProviders

/**
 * 桌面端 [BookshelfManagePlatform] 实现。
 *
 * 对照 app 端 `AndroidBookshelfManagePlatform` (内类于 BookshelfManageViewModel):
 * - **migrateBook**: 用接口默认实现 (下沉后的 `Book.migrateTo`), 与 app 端同一份代码。
 * - **getChapterFiles**: 委托 [BookStorageProviders.get].getChapterFiles(book).toHashSet()。
 * - **deleteLocalBook**: 委托 [FileBook.deleteBook] (统一入口: 清章节缓存 + 删封面 + 可选删源文件)。
 *
 * 注册: 经 [registerDesktopBookshelfManagePlatform] 注册到 shared [BookshelfManagePlatformProviders],
 * 供 [io.legado.app.ui.book.manage.BookshelfManageViewModelShared] 经
 * `BookshelfManagePlatformProviders.get()` 取用, 与 app 端注册模式一致。
 */
class DesktopBookshelfManagePlatform : BookshelfManagePlatform {

    // migrateBook 用接口默认实现 (直接调下沉后的 Book.migrateTo), 不再自写简化版

    /** 列出书籍已缓存章节文件名集合: 委托 [BookStorageProviders.get].getChapterFiles(book)。 */
    override fun getChapterFiles(book: Book): HashSet<String> {
        return BookStorageProviders.get().getChapterFiles(book).toHashSet()
    }

    /**
     * 删除本地书源文件: 委托 [FileBook.deleteBook] (与 Android 端同一条链: 清章节缓存 +
     * 删封面 + 按 deleteOriginal 删源文件)。
     */
    override fun deleteLocalBook(book: Book, deleteOriginal: Boolean) {
        FileBook.deleteBook(book, deleteOriginal)
    }
}

/**
 * 桌面端注册 BookshelfManage 平台 provider。
 *
 * 调用时机: desktop Main.kt, 在 registerDesktopWebBookProviders() 之后
 * (BookshelfManage 依赖 AppDbProviders / WebBookProviders 已注册)。
 * 模式参考 [io.legado.app.ui.book.manage.registerAndroidBookshelfManagePlatform]。
 */
fun registerDesktopBookshelfManagePlatform() {
    BookshelfManagePlatformProviders.register(DesktopBookshelfManagePlatform())
}
