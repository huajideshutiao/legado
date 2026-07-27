package io.legado.desktop.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelpAccessor
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.utils.MD5Utils
import java.nio.file.Paths

/**
 * 桌面端 [BookHelpAccessor] 实现: 委托 [BookStorageProviders.get].saveText 落盘章节正文,
 * 供 shared commonMain 中下沉的 webBook 编排层 (BookContent.kt) 通过
 * [io.legado.app.help.book.BookHelpProviders] 间接调用 saveContent。
 *
 * # 注册时机
 * desktop `main()` 中在 `BookStorageProviders.register(JvmBookStorage())` 之后注册
 * (本文件依赖 `BookStorageProviders.get()` 已就绪), 见 [io.legado.desktop.Main]。
 *
 * # 与 app 端 [io.legado.app.model.webBook.WebBookProvidersImpl] 区别
 * - app 端 `saveContent` 委托 `BookHelp.saveContent`, 内部做 saveText + saveImages
 *   + postEvent(EventBus.SAVE_CONTENT)
 * - 桌面端无 EventBus (Android 专属) / 无图片下载能力 (BitmapFactory 专属),
 *   仅委托 `BookStorageProviders.get().saveText` 写文本缓存, 与桌面端阅读流
 *   (ReadBookViewModelShared.loadChapter → BookStorageProviders.get().getContent) 对齐
 *
 * # bookSource 参数
 * 桌面端不下载图片, [bookSource] 参数忽略 (仅文本落盘); 保留接口签名一致性
 * 供未来扩展 (如桌面端引入图片缓存后补 saveImages 逻辑)。
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
        // bookSource 在桌面端忽略: 不下载图片, 仅落盘文本
        BookStorageProviders.get().saveText(book, bookChapter, content)
    }

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
}
