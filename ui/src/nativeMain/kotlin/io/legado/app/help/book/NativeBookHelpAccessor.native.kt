package io.legado.app.help.book

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.postEvent

/**
 * iOS/鸿蒙 (Native target) 共用 [BookHelpAccessor] 实现。
 *
 * # 所在源集
 * 章节正文落盘走 core 的 [BookStorageProviders], 章节图片下载编排走 ui 模块 commonMain 的
 * [BookImageDownloadShared] (core 不依赖 ui, 看不到该编排), 故本实现放在 ui 的 nativeMain
 * (iOS/鸿蒙共用); 平台注册仍由 [io.legado.app.help.config.registerIosProviders] /
 * [io.legado.app.help.config.registerOhosProviders] 经 [registerNativeBookHelpAccessor] 完成。
 *
 * # 行为
 * - [saveContent]: 委托 [BookStorageProviders] 的 saveText 落盘正文, 落盘成功后发 SAVE_CONTENT
 *   (管理页与目录页据此更新缓存状态)
 * - [saveImages] / [saveImage]: 委托共享编排 [BookImageDownloadShared] (存在性短路 + 同图去重 +
 *   插件源图片分流 + 解密脚本 + 校验失败仍落盘); 落盘走 [BookImageStorageProviders] 的
 *   images 缓存目录 (与阅读页取图的 getImagePath 同源, 下载后阅读页即可命中缓存)
 * - [hasImageContent]: 委托 [BookImageStorageProviders], 供缓存调度跳过已有整本图片缓存的书
 * - [getImage] / [isImageExist]: 用接口默认实现 (native 图片管线走
 *   [io.legado.app.help.image.ReaderImageResolver], 不经 ImageProvider, 不会调用这两个方法)
 *
 * 图片部分与 desktop [io.legado.desktop.help.book.DesktopBookHelpAccessor] 同构。
 */
class NativeBookHelpAccessor : BookHelpAccessor {

    /**
     * 保存章节正文到 Native 端缓存文件
     * (`{AppFilesDirs.filesDir}/book_cache/{bookFolderName}/{chapterFileName}`)。
     *
     * 实际落盘由 [BookStorageProviders] 的 saveText 完成 (kotlin.io.File.writeText, UTF-8 编码),
     * 行为与 app 端 BookHelp.saveText (FileUtils.createFileIfNotExist + writeText) 等价。
     * 图片由 [saveImages] 单独负责。
     */
    override fun saveContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        BookStorageProviders.get().saveText(book, bookChapter, content)
        if (BookStorageProviders.get().hasContent(book, bookChapter)) {
            postEvent(EventBus.SAVE_CONTENT, Pair(book, bookChapter))
        }
    }

    /**
     * 批量下载章节内图片 (漫画/插画): 编排下沉 [BookImageDownloadShared.saveImages]
     * (提取正文 img src → 并发 → 下载 → 解密 → 校验 → 落盘)。
     */
    override suspend fun saveImages(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String,
        concurrency: Int,
    ) {
        BookImageDownloadShared.saveImages(bookSource, book, bookChapter, content, concurrency)
    }

    /**
     * 下载并保存单张图片: 编排下沉 [BookImageDownloadShared.saveImage]
     * (带书源防盗链 header / cookie / JS, 含插件源图片分流与解密)。
     * 失败只记日志不抛出 (与 app 端一致)。
     */
    override suspend fun saveImage(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter?,
    ) {
        BookImageDownloadShared.saveImage(bookSource, book, src, chapter)
    }

    /** 图片是否已有缓存 (供缓存调度短路整本图片已缓存的书)。 */
    override fun hasImageContent(book: Book, bookChapter: BookChapter): Boolean = runCatching {
        BookImageStorageProviders.get().hasImageContent(book)
    }.getOrDefault(false)

    /**
     * 封面文件路径: `{AppFilesDirs.filesDir}/covers/{md5_16(bookUrl)}.jpg`。
     *
     * 与 desktop [io.legado.desktop.help.book.DesktopBookHelpAccessor.getCoverPath] /
     * [io.legado.app.model.fileBook.NativeFileBookAccessor.getCoverPath] 同源派生,
     * 供 CbzFile.upBookInfo 在 book.coverUrl 为空时设置封面落盘路径。
     */
    override fun getCoverPath(bookUrl: String): String {
        val filesDir = AppFilesDirs.get().filesDir
        val root = if (filesDir.endsWith("/")) filesDir.dropLast(1) else filesDir
        return "$root/covers/${MD5Utils.md5Encode16(bookUrl)}.jpg"
    }

    // BookHelpShared 下沉新增: 平台专属临时文件清理 (Native 端无 ArchiveUtils.TEMP_PATH 等, no-op)
    override suspend fun clearCacheExtra() {
        // Native 端无平台专属临时文件需清理
    }
}

/**
 * 注册 [NativeBookHelpAccessor] 到 [BookHelpProviders] (iOS/鸿蒙共用)。
 *
 * 前置依赖: [BookStorageProviders] / [BookImageStorageProviders] 已注册
 * (saveContent 委托前者落盘, saveImages/saveImage 委托后者写图片缓存)。
 */
fun registerNativeBookHelpAccessor() {
    BookHelpProviders.register(NativeBookHelpAccessor())
}
