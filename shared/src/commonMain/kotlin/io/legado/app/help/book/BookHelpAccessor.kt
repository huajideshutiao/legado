package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource

/**
 * BookHelp 跨模块只读访问接口。
 *
 * BookHelp 重 Android 依赖 (BitmapFactory/ParcelFileDescriptor/DocumentFile/appCtx),
 * 留 app 端。本接口仅暴露 webBook 编排层 (BookContent) 及下沉的 CacheBook 调度
 * (CacheBookShared) 用到的方法, 由 app 端 WebBookProvidersImpl 包装 BookHelp 实现,
 * 在 App.onCreate 经 [BookHelpProviders.register] 注册。
 *
 * 桌面端 DesktopBookHelpAccessor 仅实现 saveContent (无图片缓存, 其余走默认实现)。
 *
 * 模式参考 BookInfoRefreshers / SourceDebugLoggers。
 */
interface BookHelpAccessor {

    /**
     * 保存章节正文 (对应 BookHelp.saveContent)。
     *
     * 实现内部由 app 端 BookHelp.saveContent 落盘 + 发 EventBus 事件,
     * 行为与原 app 端直接调用完全一致。
     */
    fun saveContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String
    )

    /**
     * 保存章节内图片 (漫画/插画, 对应 BookHelp.saveImages)。
     *
     * 仅 app 端有真实实现 (BitmapFactory + ChapterContentParser.extractImages + 并发下载);
     * 桌面端无图片缓存管理能力, 默认 no-op (与原 DesktopCacheBook 跳过 saveImages 行为一致)。
     *
     * suspend 因 BookHelp.saveImages 内部 flowImages.onEachParallel.collect 为挂起调用。
     */
    suspend fun saveImages(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String,
        concurrency: Int
    ) {
        // 默认 no-op: 桌面端/iOS/鸿蒙无图片缓存管理
    }

    /**
     * 检测图片是否已完整下载 (对应 BookHelp.hasImageContent)。
     *
     * 仅 app 端有真实实现 (BitmapFactory.Options.inJustDecodeBounds 解码探测);
     * 桌面端无图片缓存, 默认返回 false (与原 DesktopCacheBook 跳过该分支行为一致)。
     */
    fun hasImageContent(book: Book, bookChapter: BookChapter): Boolean = false

    /**
     * 读取已缓存章节正文 (对应 BookHelp.getContent)。
     *
     * 默认委托 [io.legado.app.help.book.BookStorageProviders.get().getContent],
     * app 端 WebBookProvidersImpl override 委托 BookHelp.getContent (行为一致,
     * 内部同样走缓存文件读取, 仅多一层 BookHelp 间接)。
     *
     * 返回 null 表示未缓存或文件为空。
     */
    fun getContent(book: Book, bookChapter: BookChapter): String? =
        BookStorageProviders.get().getContent(book, bookChapter)

    /**
     * 书籍缓存目录路径 (对应 app 端 `BookHelp.cachePath`)。
     *
     * 用于 [io.legado.app.model.fileBook.CbzFile] 存放 `cbz_images.json` 等
     * 书籍级缓存文件。Android: `appCtx.externalFiles/book_cache`; 桌面:
     * `~/.legado/book_cache` (与 [io.legado.app.help.book.BookStorage.rootPath] 同源)。
     *
     * 默认委托 [io.legado.app.help.book.BookStorageProviders.get().rootPath],
     * app 端 WebBookProvidersImpl override 返回 `BookHelp.cachePath` (行为一致)。
     */
    val cachePath: String
        get() = BookStorageProviders.get().rootPath

    /**
     * 获取书籍封面文件路径 (对应 app 端 `FileBook.getCoverPath(bookUrl)`)。
     *
     * 用于 [io.legado.app.model.fileBook.CbzFile.upBookInfo] 在 `book.coverUrl`
     * 为空时设置封面落盘路径。路径派生: `{externalFiles/covers}/{md5(bookUrl)}.jpg`
     * (Android) 或 `{desktopAppRootDir}/covers/{md5(bookUrl)}.jpg` (桌面)。
     *
     * app 端 WebBookProvidersImpl override 委托 `FileBook.getCoverPath(bookUrl)`
     * (行为一致); 桌面端 DesktopBookHelpAccessor 自行构造路径。
     *
     * @param bookUrl 书籍唯一标识 (book.bookUrl)
     * @return 封面 JPEG 文件绝对路径
     */
    fun getCoverPath(bookUrl: String): String

    /**
     * 清理平台专属临时文件 (对应 app 端 `BookHelp.clearInvalidCache` 末段)。
     *
     * app 端 override 清理 `ArchiveUtils.TEMP_PATH` + `filesDir/share*.json` + `books.json`;
     * 其他端无此类临时文件, 默认 no-op (与 [saveImages] 默认 no-op 模式一致)。
     */
    suspend fun clearCacheExtra()
}

/**
 * BookHelp provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用
 * `BookHelpProviders.get().saveContent(...)` 替代
 * 原 `BookHelp.saveContent(...)`, 行为完全一致, 仅多一层 provider 间接。
 */
object BookHelpProviders {
    @Volatile
    private var impl: BookHelpAccessor? = null

    /** 宿主启动早期注册一次(任何 webBook 调用之前)。 */
    fun register(impl: BookHelpAccessor) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): BookHelpAccessor = impl ?: error("BookHelpProviders not registered")
}
