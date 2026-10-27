package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.FileUtils
import java.io.File

/**
 * [BookImageStorage] 安卓端 actual 实现: 委托 [BookHelp] 完成书籍图片缓存 I/O。
 *
 * 注册: app 端 [io.legado.app.App.onCreate] 早期调用
 * [BookImageStorageProviders.register] 注入本 object (在任何 BookContent 调用之前),
 * 供 shared commonMain 下沉的 webBook 编排层通过 provider 间接调用。
 *
 * 行为与原 `BookHelp.saveImage / getImage / isImageExist` 直接调用完全一致,
 * 仅多一层 provider 间接; 桌面 JVM 端对应
 * [io.legado.app.help.book.JvmBookImageStorage] (java.nio.file.Path + OkHttp 实现)。
 *
 * 模式参考 [BookStorageProviders] / [BookHelpProviders]。
 */
object AndroidBookImageStorage : BookImageStorage {

    /**
     * 保存单张图片字节到缓存, 返回保存后的本地路径 (失败返回 null)。
     *
     * 委托 [BookHelp.writeImage] 落盘 + [BookHelp.getImage] 取路径。
     * 空字节数组视为无效, 返回 null。
     */
    override fun saveImage(
        book: Book,
        chapter: BookChapter,
        url: String,
        bytes: ByteArray
    ): String? {
        if (bytes.isEmpty()) return null
        return try {
            BookHelp.writeImage(book, url, bytes)
            BookHelp.getImage(book, url).absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** 取图片本地路径; 文件不存在返回 null, 委托 [BookHelp.getImage]。 */
    override fun getImagePath(book: Book, chapter: BookChapter, url: String): String? {
        return BookHelp.getImage(book, url).takeIf { it.exists() }?.absolutePath
    }

    /** 判断图片是否已缓存到本地, 委托 [BookHelp.isImageExist]。 */
    override fun isImageExist(book: Book, chapter: BookChapter, url: String): Boolean =
        BookHelp.isImageExist(book, url)

    /** 删除单张图片缓存, 委托 [BookHelp.getImage] 定位后删除文件。 */
    override fun deleteImage(book: Book, chapter: BookChapter, url: String) {
        BookHelp.getImage(book, url).delete()
    }

    /** 清空指定书的图片缓存 (整本书 images 目录)。 */
    override fun clearCache(book: Book) {
        val imageDir = imagesDir(book)
        if (imageDir.exists()) {
            FileUtils.delete(imageDir, deleteRootDir = true)
        }
    }

    /** 校验字节流是否为有效图片, 委托 [BookHelp.checkImage] (BitmapFactory + SVG 兼底)。 */
    override fun checkImageBytes(bytes: ByteArray): Boolean = BookHelp.checkImage(bytes)

    /** 原始字节落盘 (不校验), 委托 [BookHelp.writeImage]。 */
    override fun writeImageBytes(book: Book, chapter: BookChapter, url: String, bytes: ByteArray) {
        BookHelp.writeImage(book, url, bytes)
    }

    /**
     * 取书籍 images 缓存子目录 (`book_cache/{bookFolderName}/images`)。
     *
     * 通过 [BookHelp.getImage] 派生路径 (用一个固定探测 url), 取其 parentFile 即 images 目录,
     * 避免直接依赖 [BookHelp] 私有的 downloadDir / cacheFolderName / cacheImageFolderName。
     */
    private fun imagesDir(book: Book): File =
        BookHelp.getImage(book, "probe").parentFile ?: File("")
}
