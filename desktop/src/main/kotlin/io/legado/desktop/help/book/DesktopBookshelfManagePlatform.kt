package io.legado.desktop.help.book

import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.ui.book.manage.BookshelfManagePlatform
import io.legado.app.ui.book.manage.BookshelfManagePlatformProviders
import io.legado.app.ui.compose.platform.jvmGetString

/**
 * 桌面端 [BookshelfManagePlatform] 实现。
 *
 * 对照 app 端 `AndroidBookshelfManagePlatform` (内类于 BookshelfManageViewModel):
 * - **migrateBook**: 用接口默认实现 (下沉后的 `Book.migrateTo`), 与 app 端同一份代码。
 * - **clearCache**: 委托 [BookStorageProviders.get].clearCache(book) (JvmBookStorage 实现)。
 * - **getChapterFiles**: 委托 [BookStorageProviders.get].getChapterFiles(book).toHashSet()。
 * - **deleteLocalBook**: 委托 [LocalBookLocators.get].deleteBook(book) (JvmLocalBookLocator 实现,
 *   desktop Main.kt 注册)。deleteOriginal 参数在桌面端忽略 (本地书直接删除源文件,
 *   与 app 端 FileBook.deleteBook(book, deleteOriginal) 行为对齐: deleteOriginal=true 删源文件,
 *   deleteOriginal=false 仅删数据库记录由调用方处理)。
 * - **clearCacheSuccessMessage**: 硬编码 "清缓存成功" (桌面端无 R.string 资源系统)。
 *
 * 注册: 经 [registerDesktopBookshelfManagePlatform] 注册到 shared [BookshelfManagePlatformProviders],
 * 供 [io.legado.app.ui.book.manage.BookshelfManageViewModelShared] 经
 * `BookshelfManagePlatformProviders.get()` 取用, 与 app 端注册模式一致。
 */
class DesktopBookshelfManagePlatform : BookshelfManagePlatform {

    // migrateBook 用接口默认实现 (直接调下沉后的 Book.migrateTo), 不再自写简化版

    /** 清除书籍章节缓存: 委托 [BookStorageProviders.get].clearCache(book) (JvmBookStorage)。 */
    override fun clearCache(book: Book) {
        BookStorageProviders.get().clearCache(book)
    }

    /** 列出书籍已缓存章节文件名集合: 委托 [BookStorageProviders.get].getChapterFiles(book)。 */
    override fun getChapterFiles(book: Book): HashSet<String> {
        return BookStorageProviders.get().getChapterFiles(book).toHashSet()
    }

    /**
     * 删除本地书源文件: 委托 [LocalBookLocators.get].deleteBook(book) (JvmLocalBookLocator)。
     *
     * deleteOriginal 参数在桌面端忽略 (本地书直接删除源文件, 与 app 端 FileBook.deleteBook(book, true)
     * 行为一致; deleteOriginal=false 场景仅删数据库记录, 由调用方 BookshelfManageViewModelShared.deleteBook
     * 在 dao.delete 后判断 isLocal 决定是否调本方法, 故本方法被调用即意味着 deleteOriginal=true)。
     */
    override fun deleteLocalBook(book: Book, deleteOriginal: Boolean) {
        if (deleteOriginal) {
            LocalBookLocators.get().deleteBook(book)
        }
    }

    /** 清缓存成功提示文案 (硬编码, 与 app 端 R.string.clear_cache_success 对应)。 */
    override val clearCacheSuccessMessage: String = jvmGetString("clear_cache_success")
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
