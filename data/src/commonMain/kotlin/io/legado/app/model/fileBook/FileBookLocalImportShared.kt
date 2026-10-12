package io.legado.app.model.fileBook

import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.exception.EmptyFileException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString

/**
 * 本地文件导入的判型 → 构造 Book → 落库 (四端唯一一份, 对应原 `FileBook.importLocalFile(FileDoc)`).
 *
 * 判型 (与各端逐字一致的那三条):
 * - `.cbz` → 图片书;
 * - 压缩包 (zip/rar/7z/...) 内含书籍文件 → 交 [FileBookAccessor.importFromArchive] 解包导入该书
 *   (压缩包分支内不再自己查条目名, 判据由注入的 [archiveHasBookFile] 回答);
 * - 其余 (含压缩包但内无书籍文件) → 按普通文件导入。
 *
 * # 平台差异 (注入, 不在共享层写分支)
 * - [archiveHasBookFile]: 本端解包原语能否在本压缩包里找到书籍文件。本端没有该格式的解包器时
 *   由本端实现直接抛明确异常 (iOS/鸿蒙无 libarchive, 除 zip 外的压缩包格式如此), 共享层不按端判断;
 * - [ref] / [storedRef] / [fileName] / [size] / [lastModified]: 各端文件抽象 (Android FileDoc /
 *   桌面 java.io.File / native kotlin.io.File) 取值不同, 由调用方取好传入。
 *
 * 写盘只经注入的 [accessor] (saveBookFile / importFromArchive), 共享层不直接碰文件: iOS/鸿蒙的
 * 授权装饰器包住整个 [importLocalFile] 调用, 探测与解包都发生在租约有效期内。
 */
object FileBookLocalImportShared {

    /**
     * @param accessor 平台文件原语 (条目名不可读时由其实现报错)
     * @param ref 平台文件引用原样 (压缩包解包走它)
     * @param fileName 文件名 (含后缀, 判型与书名作者分析都用它)
     * @param size 文件字节数 (0 视作空文件, 与各端原判据一致)
     * @param lastModified 文件最后修改时间
     * @param storedRef 落库存储引用 (bookUrl 与 origin 同值)
     * @param archiveHasBookFile 压缩包内是否含书籍文件; 本端无该格式解包器时抛明确异常
     */
    suspend fun importLocalFile(
        accessor: FileBookAccessor,
        ref: String,
        fileName: String,
        size: Long,
        lastModified: Long,
        storedRef: String,
        archiveHasBookFile: (String) -> Boolean,
    ): Book {
        if (size == 0L) throw EmptyFileException("Unexpected empty File")
        val (name, author) = accessor.analyzeNameAuthor(fileName)
        var type = BookType.text or BookType.local
        when {
            fileName.endsWith(".cbz", true) -> type = BookType.image or BookType.local
            AppPattern.archiveFileRegex.matches(fileName) -> {
                if (archiveHasBookFile(ref)) {
                    return accessor.importFromArchive(ref) { FileBook.isBookFile(it) }.firstOrNull()
                        ?: throw NoStackTraceException(appString(AppStringKey.unsupport_archivefile_entry))
                }
            }
        }
        return FileBookRemoteImportShared.importBook(
            Book(
                bookUrl = storedRef,
                name = name,
                author = author,
                originName = fileName,
                latestChapterTime = lastModified,
                order = AppDbProviders.get().bookDao.minOrder() - 1,
                origin = storedRef,
                type = type
            )
        )
    }
}
