package io.legado.desktop.help.book

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelpAccessor
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.ui.book.read.page.provider.ChapterContentParserShared
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * 桌面端 [BookHelpAccessor] 实现: 委托 [BookStorageProviders.get].saveText 落盘章节正文,
 * 图片下载复用 [BookImageStorageProviders] (JvmBookImageStorage) + 共享正文图片提取
 * ([ChapterContentParserShared]), 供 shared commonMain 中下沉的 webBook 编排层 (BookContent.kt)
 * 通过 [io.legado.app.help.book.BookHelpProviders] 间接调用 saveContent / saveImages。
 *
 * # 注册时机
 * desktop `main()` 中在 `BookStorageProviders.register(JvmBookStorage())` 之后注册
 * (本文件依赖 `BookStorageProviders.get()` 已就绪), 见 [io.legado.desktop.Main]。
 *
 * # 与 app 端 [io.legado.app.model.webBook.WebBookProvidersImpl] 区别
 * - app 端 `saveContent` 委托 `BookHelp.saveContent`, 内部做 saveText + postEvent
 *   (图片由 CacheBookShared 另调 [saveImages]); 桌面端 saveContent 同样只落盘文本
 * - [saveImages] / [saveImage]: 用 [AnalyzeUrlFactories] 带书源防盗链头下载 (与 app 端
 *   BookHelp.saveImage 同链路), 落盘走 JvmBookImageStorage 的 images 缓存目录 (与阅读页
 *   BookImageStorageProviders.getImagePath 同源, 下载后阅读页即可命中缓存)
 *
 * 模式参考 app 端 WebBookProvidersImpl 的 BookHelpAccessor 实现段。
 */
class DesktopBookHelpAccessor : BookHelpAccessor {

    /**
     * 保存章节正文到桌面端缓存文件 (~/.legado/book_cache/{bookFolderName}/{chapterFileName})。
     *
     * 实际落盘由 [io.legado.app.help.book.JvmBookStorage.saveText] 完成
     * (java.nio.file.Files.write, UTF-8 编码), 行为与 app 端 BookHelp.saveText
     * (FileUtils.createFileIfNotExist + writeText) 等价, 仅路径不同。
     */
    override fun saveContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        // 与 app 端一致: saveContent 只落盘文本 (图片由 CacheBookShared 走 saveImages)
        BookStorageProviders.get().saveText(book, bookChapter, content)
    }

    /**
     * 批量下载章节内图片 (漫画/插画): 提取正文 <img> src → 并发下载 → 写入 images 缓存。
     *
     * 对照 app 端 BookHelp.saveImages (flowImages + onEachParallel), 提取走 shared
     * [ChapterContentParserShared.extractImages], 下载走 [saveImage]。
     */
    override suspend fun saveImages(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String,
        concurrency: Int,
    ) {
        coroutineScope {
            val urls = ChapterContentParserShared.extractImages(content)
                .map { it.src }
                .filter { it.isNotBlank() }
            urls.map { src ->
                async(Dispatchers.IO) { saveImage(bookSource, book, src, bookChapter) }
            }.awaitAll()
        }
    }

    /**
     * 下载并保存单张图片 (带书源防盗链 header / cookie / JS, 与 app 端 BookHelp.saveImage 同链路)。
     *
     * 已存在跳过; 失败只记日志不抛出 (与 app 端一致)。
     */
    override suspend fun saveImage(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter?,
    ) {
        val storage = runCatching { BookImageStorageProviders.get() }.getOrNull()
            ?: return
        val ch = chapter ?: BookChapter(url = src, bookUrl = book.bookUrl)
        if (storage.isImageExist(book, ch, src)) return
        withContext(Dispatchers.IO) {
            runCatching {
                val analyzeUrl = AnalyzeUrlFactories.create(
                    src,
                    source = bookSource,
                    coroutineContext = currentCoroutineContext(),
                )
                val bytes = analyzeUrl.getByteArrayAwait()
                if (bytes.isNotEmpty()) storage.saveImage(book, ch, src, bytes)
            }.onFailure {
                AppLog.put(
                    "${book.name} ${chapter?.title} 图片 $src 下载失败\n${it.localizedMessage}",
                    it
                )
            }
        }
    }

    /** 图片是否已缓存 (供 CacheBookShared.hasImageContent 分支短路重复下载)。 */
    override fun hasImageContent(book: Book, bookChapter: BookChapter): Boolean = runCatching {
        BookImageStorageProviders.get().hasImageContent(book)
    }.getOrDefault(false)

    /**
     * 封面文件路径 (CbzFile 下沉新增, 对应 app 端 `FileBook.getCoverPath(bookUrl)`)。
     *
     * 路径派生: `{desktopAppRootDir}/covers/{md5_16(bookUrl)}.jpg`
     * - 便携模式: `data/covers/{md5_16}.jpg`
     * - 开发模式: `~/.legado/covers/{md5_16}.jpg`
     *
     * 与 app 端 `{appCtx.externalFiles}/covers/{md5_16}.jpg` 语义对齐 (仅根目录不同),
     * 供 [io.legado.app.model.fileBook.CbzFile.upBookInfo] 在 book.coverUrl 为空时设置封面落盘路径。
     */
    override fun getCoverPath(bookUrl: String): String {
        val root = desktopAppRootDir()
        return Paths.get(root, "covers", "${MD5Utils.md5Encode16(bookUrl)}.jpg").toString()
    }

    // BookHelpShared 下沉新增: 平台专属临时文件清理 (桌面端无 ArchiveUtils.TEMP_PATH 等, no-op)
    override suspend fun clearCacheExtra() {
        // 桌面端无平台专属临时文件需清理
    }
}
