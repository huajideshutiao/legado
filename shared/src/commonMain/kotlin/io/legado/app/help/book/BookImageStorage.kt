package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

/**
 * 书籍图片缓存 I/O 跨平台抽象。
 *
 * 安卓端 [io.legado.app.help.book.BookHelp] 的 saveImage / getImage /
 * isImageExist / hasImageContent 等方法重 Android 依赖 (BitmapFactory 校验 /
 * ImageUtils.decode / AnalyzeUrl.getByteArrayAwait), 留 app 端。本接口仅暴露
 * 图片缓存读写相关方法, 供桌面 JVM / iOS / 鸿蒙 commonMain 业务调用。
 *
 * 实现注册:
 * - Android: app 端启动早期调用 [BookImageStorageProviders.register] 注入委托给
 *   [io.legado.app.help.book.BookHelp] 的实现 (本任务不涉及, 后续任务做)。
 * - 桌面 JVM: desktop 模块启动时注入 [io.legado.app.help.book.JvmBookImageStorage]
 *   (java.nio.file.Path + OkHttp 实现)。
 * - iOS / 鸿蒙: 暂未实现 (stub), 后续 KP3 / KP4 补。
 *
 * 模式参考 [BookStorageProviders] / [io.legado.app.help.book.BookHelpProviders]。
 */
interface BookImageStorage {

    /**
     * 保存单张图片字节到缓存, 返回保存后的本地路径 (失败返回 null)。
     *
     * [url] 用于派生文件名 (MD5(url).suffix), 避免重名。
     * 实现内部应做图片有效性校验 (损坏字节返回 null)。
     */
    fun saveImage(book: Book, chapter: BookChapter, url: String, bytes: ByteArray): String?

    /**
     * 取图片本地路径; 文件不存在返回 null。
     *
     * 注意: 与 [saveImage] 的 [url] 参数一致, 同一 url 解析到同一文件名。
     */
    fun getImagePath(book: Book, chapter: BookChapter, url: String): String?

    /**
     * 判断图片是否已缓存到本地。
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.isImageExist]。
     */
    fun isImageExist(book: Book, chapter: BookChapter, url: String): Boolean

    /**
     * 检测该书是否有图片缓存目录 (用于漫画类书籍判断)。
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.hasImageContent] 的
     * "目录是否存在" 部分 (不校验图片有效性)。
     */
    fun hasImageContent(book: Book): Boolean

    /**
     * 清空指定书的图片缓存 (整本书 images 目录)。
     */
    fun clearCache(book: Book)

    /**
     * 批量下载并保存图片 (供 saveImages 编排层调用)。
     *
     * @param urls 待下载图片 URL 列表
     * @return 全部成功 true; 任一失败 (网络 / IO / 校验) false
     *         (部分已保存的文件保留, 由调用方决定是否清理)
     */
    suspend fun saveImages(book: Book, chapter: BookChapter, urls: List<String>): Boolean
}

/**
 * [BookImageStorage] provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `BookImageStorageProviders.get().saveImage(...)` 替代
 * 原 `BookHelp.saveImage(...)`, 行为完全一致, 仅多一层 provider 间接。
 */
object BookImageStorageProviders {
    @Volatile
    private var impl: BookImageStorage? = null

    /** 宿主启动早期注册一次 (任何 BookContent 调用之前)。 */
    fun register(impl: BookImageStorage) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): BookImageStorage = impl ?: error("BookImageStorageProviders not registered")
}
