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

    /**
     * 检测该书是否有图片缓存目录 (用于漫画类书籍判断)。
     *
     * 接口语义是"目录是否存在" (不校验图片有效性), 通过 [BookHelp.getImage] 的
     * parentFile 定位 images 子目录, 检查存在且非空。
     * 与 [BookHelp.hasImageContent]`(book, chapter)` (按章节校验图片完整性) 不同,
     * 此处仅判断整本书的 images 目录是否有内容。
     */
    override fun hasImageContent(book: Book): Boolean {
        val imageDir = imagesDir(book)
        return imageDir.exists() && (imageDir.listFiles()?.isNotEmpty() == true)
    }

    /** 清空指定书的图片缓存 (整本书 images 目录)。 */
    override fun clearCache(book: Book) {
        val imageDir = imagesDir(book)
        if (imageDir.exists()) {
            FileUtils.delete(imageDir, deleteRootDir = true)
        }
    }

    /**
     * 批量下载并保存图片。
     *
     * 接口传 [urls] 列表, 适配到 [BookHelp.saveImage] (单张下载, 内部含去重 / 互斥锁 /
     * 解码校验)。逐张下载后用 [BookHelp.isImageExist] 校验落盘成功;
     * 任一失败返回 false (部分已保存的文件保留, 由调用方决定是否清理)。
     *
     * [chapter] 参数透传给 [BookHelp.saveImage] 用于日志。
     */
    override suspend fun saveImages(
        book: Book,
        chapter: BookChapter,
        urls: List<String>
    ): Boolean {
        return urls.all { url ->
            try {
                BookHelp.saveImage(null, book, url, chapter)
                BookHelp.isImageExist(book, url)
            } catch (e: Exception) {
                false
            }
        }
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
