package io.legado.app.ui.book.manage

import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.LocalBookLocators

// iOS/鸿蒙书架管理平台实现: 复用 commonMain 的 BookStorageProviders/LocalBookLocators
class NativeBookshelfManagePlatform : BookshelfManagePlatform {

    // migrateBook 用接口默认实现 (直接调下沉后的 Book.migrateTo), 不再自写简化版

    // 清除书籍章节缓存: 委托 BookStorageProviders (NativeBookStorage 实现)
    override fun clearCache(book: Book) {
        BookStorageProviders.get().clearCache(book)
    }

    // 列出已缓存章节文件名集合: 委托 BookStorageProviders
    override fun getChapterFiles(book: Book): HashSet<String> {
        return BookStorageProviders.get().getChapterFiles(book).toHashSet()
    }

    // 删除本地书源文件: 委托 LocalBookLocators (NativeLocalBookLocator 实现)
    override fun deleteLocalBook(book: Book, deleteOriginal: Boolean) {
        if (deleteOriginal) {
            LocalBookLocators.get().deleteBook(book)
        }
    }

    // iOS/鸿蒙无 R.string 资源系统, 硬编码文案 (与 desktop jvmGetString fallback 一致)
    override val clearCacheSuccessMessage: String = "清缓存成功"
}

// iOS/鸿蒙宿主注册入口 (对照 app 端 registerAndroidBookshelfManagePlatform)
fun registerNativeBookshelfManagePlatform() {
    BookshelfManagePlatformProviders.register(NativeBookshelfManagePlatform())
}
