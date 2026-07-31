package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import kotlin.concurrent.Volatile

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
 * - iOS / 鸿蒙: 共用 nativeMain [io.legado.app.help.book.NativeBookStorage]
 *   (`kotlin.io.File` 实现)。
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
     * 判断书籍缓存目录下某个文件是否存在 (按文件名, 不含路径)。
     *
     * 与 [getChapterFiles] 不同: 不做本地 txt / 视频 / 音频短路, 直查文件系统,
     * 对应 Android 端原 `File(path).exists()`。供 `.nr` 标记等非章节正文文件使用。
     */
    fun hasCacheFile(book: Book, fileName: String): Boolean

    /**
     * 读取书籍缓存目录下指定文件的文本; 文件不存在返回 null (空文件返回空串)。
     *
     * 与 [getContent] 不同: 不做 "空内容视为 null" 归一, 由调用方区分。
     */
    fun readCacheFile(book: Book, fileName: String): String?

    /** 在书籍缓存目录下创建空文件 (父目录自动创建, 已存在则保留原内容)。 */
    fun createCacheFile(book: Book, fileName: String)

    /** 删除书籍缓存目录下指定文件 (不存在则忽略)。 */
    fun deleteCacheFile(book: Book, fileName: String)

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

    /**
     * 清除无效缓存文件夹 (删除不在书架的 + 按图片子目录名做 512MB 超量淘汰)。
     *
     * 编排逻辑对照 [io.legado.app.help.book.BookHelpShared.clearInvalidCache] 步骤 1+2:
     * 1. 删除缓存根目录下不在 [validFolderNames] 中的书籍缓存文件夹
     * 2. 对含 [imageSubFolderName] 子目录的文件夹按最旧优先淘汰, 总大小收敛到 [maxSize] 以内
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.clearInvalidCache] 中
     * "删除不在书架的书籍缓存 + 漫画图片缓存管理 (512MB)" 段。
     */
    fun clearInvalidBookFolders(
        validFolderNames: Set<String>,
        imageSubFolderName: String,
        maxSize: Long
    )
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
