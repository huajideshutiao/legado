package io.legado.desktop.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelpChapterLocator
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.ui.book.manage.BookshelfManagePlatform
import io.legado.app.ui.book.manage.BookshelfManagePlatformProviders
import io.legado.app.ui.compose.platform.jvmGetString

/**
 * 桌面端 [BookshelfManagePlatform] 实现。
 *
 * 对照 app 端 `AndroidBookshelfManagePlatform` (内类于 BookshelfManageViewModel):
 * - **migrateBook**: app 端委托 `Book.migrateTo(newBook, toc)` (内部走 BookHelp.getDurChapter +
 *   ContentProcessor.getTitleReplaceRules + getUseReplaceRule)。
 *   桌面端 [migrateBook] 走 [BookHelpChapterLocator.getDurChapter] (与 app 端同一份算法),
 *   不走 ContentProcessor 标题替换规则,
 *   直接取 toc[newIndex].title 作为新章节标题。
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

    /**
     * 迁移旧书信息到新书 (对照 `Book.migrateTo(newBook, toc)`)。
     *
     * 1. 用 [BookHelpChapterLocator.getDurChapter] 章节名相似度匹配定位新书当前章节索引;
     * 2. 取 toc[newIndex].title 作为新 durChapterTitle (不走 ContentProcessor 标题替换规则);
     * 3. 复制 durChapterPos / durChapterTime / group / order / customCoverUrl / customIntro /
     *    customTag / canUpdate / readConfig 等字段 (与 app 端 Book.migrateTo 一致)。
     */
    override fun migrateBook(oldBook: Book, newBook: Book, toc: List<BookChapter>): Book {
        val newIndex = BookHelpChapterLocator.getDurChapter(
            oldDurChapterIndex = oldBook.durChapterIndex,
            oldDurChapterName = oldBook.durChapterTitle,
            newChapterList = toc,
            oldChapterListSize = oldBook.totalChapterNum,
        )
        newBook.durChapterIndex = newIndex
        // 桌面端简化: 不走 ContentProcessor.getTitleReplaceRules + getUseReplaceRule,
        // 直接取 toc[newIndex].title;
        // app 端用 toc[newIndex].getDisplayTitle(titleReplaceRules, useReplaceRules),
        // 此处差异不影响阅读功能 (仅章节标题展示, 不影响正文)
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
