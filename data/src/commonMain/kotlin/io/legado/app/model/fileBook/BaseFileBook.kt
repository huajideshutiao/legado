package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.InputStream

/**
 * 本地书籍文件解析跨平台接口 (commonMain 下沉版)。
 *
 * 原 app 端 `io.legado.app.model.fileBook.BaseFileBook` 含 7 个方法, 其中
 * `getBookInputStream` / `getLastModified` 默认实现重 Android 依赖 (appCtx /
 * DocumentFile / importFromArchive / downloadRemoteBook), 不下沉到 commonMain。
 *
 * 本接口仅保留 5 个核心解析方法, 供 [CbzFile] / [TextFile] / FileBook / EpubFile
 * 等实现类跨平台复用。`getBookInputStream` / `getLastModified` 由各平台按需以
 * 扩展函数或实现类成员方式提供 (app 端: BaseFileBookExt.kt 扩展函数; desktop:
 * FileBook 下沉后成员方法)。
 *
 * 实现类对照:
 * - [CbzFile]: CBZ/ZIP 漫画解析 (commonMain, 本任务下沉)
 * - TextFile: TXT 本地书解析 (jvmAndAndroidMain, 已下沉)
 * - FileBook: 文件导入总入口 (app 端, 待并行子代理下沉)
 * - EpubFile: EPUB 解析 (待下沉)
 *
 * 模式参考 app 端原 `interface BaseFileBook`, 仅剥离 Android 依赖默认方法。
 */
interface BaseFileBook {

    /**
     * 更新书籍信息 (书名/作者/封面/简介等, 从文件元数据提取)。
     *
     * 对应 app 端 [io.legado.app.model.fileBook.BaseFileBook.upBookInfo]。
     */
    fun upBookInfo(book: Book)

    /**
     * 获取章节列表 (从文件解析目录)。
     *
     * 对应 app 端 [io.legado.app.model.fileBook.BaseFileBook.getChapterList]。
     */
    fun getChapterList(book: Book): ArrayList<BookChapter>

    /**
     * 获取章节正文 (从文件解析内容)。
     *
     * 对应 app 端 [io.legado.app.model.fileBook.BaseFileBook.getContent]。
     */
    fun getContent(book: Book, chapter: BookChapter): String?

    /**
     * 获取图片输入流 (漫画/EPUB 内嵌图片, href 为 zip 内条目名或相对路径)。
     *
     * 对应 app 端 [io.legado.app.model.fileBook.BaseFileBook.getImage]。
     */
    fun getImage(book: Book, href: String): InputStream?

    /** 清理资源 (关闭文件句柄/缓存等)。 */
    fun clear() {}
}
