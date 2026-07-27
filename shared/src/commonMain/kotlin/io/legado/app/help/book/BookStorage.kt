package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

/**
 * 章节缓存文件 I/O 跨平台抽象。
 *
 * 安卓端 [io.legado.app.help.book.BookHelp] 重 Android 依赖
 * (BitmapFactory / ParcelFileDescriptor / DocumentFile / appCtx.externalFiles /
 * ContentResolver / StorageManager), 留 app 端。本接口仅暴露章节正文缓存
 * 读写相关方法, 供桌面 JVM / iOS / 鸿蒙 commonMain 业务调用。
 *
 * 实现注册:
 * - Android: app 端启动早期调用 [BookStorageProviders.register] 注入委托给
 *   [io.legado.app.help.book.BookHelp] 的实现 (本任务不涉及, 后续任务做)。
 * - 桌面 JVM: desktop 模块启动时注入 [io.legado.app.help.book.JvmBookStorage]
 *   (java.nio.file.Path 实现)。
 * - iOS / 鸿蒙: 暂未实现 (stub), 后续 KP3 / KP4 补。
 *
 * 模式参考 [io.legado.app.help.config.AppConfigProviders] /
 * [io.legado.app.help.book.BookHelpProviders]。
 */
interface BookStorage {

    /**
     * 章节缓存根路径。
     *
     * Android: `appCtx.externalFiles/book_cache`; 桌面: `~/.legado/book_cache`。
     */
    val rootPath: String

    /**
     * 获取书籍缓存文件夹名 (对应 [Book.getFolderName])。
     */
    fun getFolderName(book: Book): String

    /**
     * 列出书籍缓存文件夹下所有章节文件名 (不含路径)。
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.getChapterFiles]。
     */
    fun getChapterFiles(book: Book): List<String>

    /**
     * 保存章节正文到缓存文件 (覆盖写)。
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.saveText]。
     */
    fun saveText(book: Book, chapter: BookChapter, text: String)

    /**
     * 读取章节正文; 文件不存在或为空返回 null。
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.getContent]。
     */
    fun getContent(book: Book, chapter: BookChapter): String?

    /**
     * 删除该书所有章节缓存文件 (整本书缓存目录)。
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.clearCache(book)]。
     */
    fun delContent(book: Book)

    /**
     * 删除指定章节的缓存文件 (单章节, 不影响同书其他章节)。
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.delContent(book, chapter)]。
     */
    fun delContent(book: Book, chapter: BookChapter)

    /**
     * 检测指定章节是否已缓存。
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.hasContent]。
     */
    fun hasContent(book: Book, chapter: BookChapter): Boolean

    /**
     * 清空所有书的章节缓存 (整个 book_cache 目录)。
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.clearCache]。
     */
    fun clearCache()

    /**
     * 清空指定书的章节缓存。
     */
    fun clearCache(book: Book)

    /**
     * 修改书名 / 作者后, 重命名缓存文件夹 (oldBook → newBook)。
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.updateCacheFolder]。
     */
    fun updateCacheFolder(oldBook: Book, newBook: Book)

    /**
     * 清除已删除书 / 超量缓存 (按 [maxSize] 淘汰最旧的图片缓存文件夹)。
     *
     * [maxSize] 为图片缓存总大小上限 (字节), 与 Android 端 512MB 行为对齐。
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.clearInvalidCache] 中的
     * 漫画图片缓存管理段。
     */
    fun clearInvalidCache(maxSize: Long)
}

/**
 * [BookStorage] provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `BookStorageProviders.get().saveText(...)` 替代
 * 原 `BookHelp.saveText(...)`, 行为完全一致, 仅多一层 provider 间接。
 */
object BookStorageProviders {
    @Volatile
    private var impl: BookStorage? = null

    /** 宿主启动早期注册一次 (任何 BookContent 调用之前)。 */
    fun register(impl: BookStorage) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): BookStorage = impl ?: error("BookStorageProviders not registered")
}
