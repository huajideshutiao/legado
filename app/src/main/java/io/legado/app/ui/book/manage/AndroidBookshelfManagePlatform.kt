package io.legado.app.ui.book.manage

import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.model.fileBook.FileBook

/**
 * Android 端 [BookshelfManagePlatform] 实现 (顶级类)。
 *
 * 从原 `BookshelfManageViewModel` 的 inner class 提取, 供 shared
 * [BookshelfManageViewModelShared] 经 [BookshelfManagePlatformProviders] 取用。
 * 委托 [BookHelp] / [FileBook] / [appCtx] 这些重 Android 依赖 (留 app 端)。
 *
 * - [migrateBook]: 用接口默认实现 (下沉后的 `Book.migrateTo`), 不再自写。
 * - [getChapterFiles]: 委托 [BookHelp.getChapterFiles]。
 * - [getCacheSize]: 用接口默认实现 (返回 0), 与原行为一致 (原 app 端未统计缓存大小)。
 * - [deleteLocalBook]: 委托 [FileBook.deleteBook]。
 */
class AndroidBookshelfManagePlatform : BookshelfManagePlatform {

    // migrateBook / getCacheSize 用接口默认实现, 不再 override

    /** 委托 [BookHelp.getChapterFiles], 列出已缓存章节文件名集合。 */
    override fun getChapterFiles(book: Book): HashSet<String> {
        return BookHelp.getChapterFiles(book)
    }

    /** 委托 [FileBook.deleteBook], 走 DocumentFile / ContentResolver 删本地源文件。 */
    override fun deleteLocalBook(book: Book, deleteOriginal: Boolean) {
        FileBook.deleteBook(book, deleteOriginal)
    }
}

/**
 * 安卓宿主启动早期注册 BookshelfManage 平台 provider。
 *
 * 调用时机: App.onCreate, 在 `registerAndroidChangeBookSourcePlatform()` 之后
 * (BookshelfManage 依赖 AppDbProviders / WebBookProviders 已注册)。
 *
 * 模式参考 `registerAndroidChangeBookSourcePlatform` /
 * `registerAndroidAudioPlayProviders`。
 */
fun registerAndroidBookshelfManagePlatform() {
    BookshelfManagePlatformProviders.register(AndroidBookshelfManagePlatform())
}
