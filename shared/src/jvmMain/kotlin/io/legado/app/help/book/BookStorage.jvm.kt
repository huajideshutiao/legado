package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.storage.DataStorageProviders
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors

/**
 * [BookStorage] 桌面 JVM 实现。
 *
 * 章节缓存目录结构: `{desktopAppRootDir}/book_cache/{bookFolderName}/{chapterFileName}`
 * - 与桌面端数据库路径 (`{desktopAppRootDir}/legado.db`,
 *   见 [io.legado.app.data.BundledDatabaseDriver]) 同源
 * - 文件名格式由 [BookChapter.getFileName] 派生 (`00001-{titleMD5}.nb`)
 *
 * 对应 Android 端 [io.legado.app.help.book.BookHelp] 的 saveText / getContent /
 * delContent 等方法的桌面实现, 不依赖 Android FileUtils / appCtx.externalFiles。
 *
 * 注册: desktop 模块启动时通过 [BookStorageProviders.register] 注入。
 */
class JvmBookStorage(
    override val rootPath: String = defaultRootPath()
) : BookStorage {

    /** 缓存根目录 Path (构造时一次性解析, 避免每次调用重复 Paths.get)。 */
    private val rootDir: Path = Paths.get(rootPath)

    override fun getFolderName(book: Book): String = book.getFolderName()

    override fun getChapterFiles(book: Book): List<String> {
        val bookDir = rootDir.resolve(getFolderName(book))
        if (!Files.isDirectory(bookDir)) return emptyList()
        // 列出目录下所有普通文件名 (与 Android BookHelp.getChapterFiles 返回 HashSet<String> 对齐)
        // Files.list 返回 Stream 需显式 close, 用 use 包裹避免文件句柄泄漏
        return Files.list(bookDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .collect(Collectors.toList())
        }
    }

    override fun hasCacheFile(book: Book, fileName: String): Boolean {
        return Files.isRegularFile(resolveCacheFile(book, fileName))
    }

    override fun readCacheFile(book: Book, fileName: String): String? {
        val file = resolveCacheFile(book, fileName)
        if (!Files.isRegularFile(file)) return null
        return Files.readAllBytes(file).toString(Charsets.UTF_8)
    }

    override fun createCacheFile(book: Book, fileName: String) {
        val file = resolveCacheFile(book, fileName)
        Files.createDirectories(file.parent)
        if (!Files.exists(file)) {
            Files.createFile(file)
        }
    }

    override fun deleteCacheFile(book: Book, fileName: String) {
        Files.deleteIfExists(resolveCacheFile(book, fileName))
    }

    override fun saveText(book: Book, chapter: BookChapter, text: String) {
        if (text.isEmpty()) return
        val file = resolveChapterFile(book, chapter)
        // 父目录不存在则递归创建 (与 Android FileUtils.createFileIfNotExist 行为对齐)
        Files.createDirectories(file.parent)
        Files.write(file, text.toByteArray(Charsets.UTF_8))
    }

    override fun getContent(book: Book, chapter: BookChapter): String? {
        val file = resolveChapterFile(book, chapter)
        if (!Files.isRegularFile(file)) return null
        val content = Files.readAllBytes(file).toString(Charsets.UTF_8)
        // 与 Android BookHelp.getContent 行为对齐: 空字符串视为无内容
        return content.ifEmpty { null }
    }

    override fun delContent(book: Book) {
        val bookDir = rootDir.resolve(getFolderName(book))
        if (Files.isDirectory(bookDir)) {
            // 递归删除整本书缓存目录 (与 Android FileUtils.delete(filePath) 行为对齐)
            bookDir.toFile().deleteRecursively()
        }
    }

    override fun delContent(book: Book, chapter: BookChapter) {
        // 删除单个章节缓存文件 (与 Android BookHelp.delContent 行为对齐)
        // 路径: {rootPath}/{bookFolderName}/{chapterFileName}
        Files.deleteIfExists(resolveChapterFile(book, chapter))
    }

    override fun hasContent(book: Book, chapter: BookChapter): Boolean {
        return Files.isRegularFile(resolveChapterFile(book, chapter))
    }

    override fun clearCache() {
        if (Files.isDirectory(rootDir)) {
            rootDir.toFile().deleteRecursively()
        }
    }

    override fun clearCache(book: Book) {
        delContent(book)
    }

    override fun updateCacheFolder(oldBook: Book, newBook: Book) {
        // 与 Android BookHelp.updateCacheFolder 行为对齐: 用 getFolderNameNoCache 比较 + move
        val oldName = oldBook.getFolderNameNoCache()
        val newName = newBook.getFolderNameNoCache()
        if (oldName == newName) return
        val oldDir = rootDir.resolve(oldName)
        val newDir = rootDir.resolve(newName)
        if (!Files.isDirectory(oldDir)) return
        Files.createDirectories(newDir.parent)
        Files.move(oldDir, newDir, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun clearInvalidCache(maxSize: Long) {
        // 统一编排下沉 [BookHelpShared.evictMangaCache] (原本地 evictMangaCache 平行实现已删)
        runBlocking {
            BookHelpShared.evictMangaCache(rootPath, BookHelpShared.cacheImageFolderName, maxSize)
        }
    }

    override fun clearInvalidBookFolders(
        validFolderNames: Set<String>,
        imageSubFolderName: String,
        maxSize: Long
    ) {
        // 统一编排下沉 [BookHelpShared.clearInvalidBookFolders] (原本地实现已删)
        runBlocking {
            BookHelpShared.clearInvalidBookFolders(
                rootPath, validFolderNames, imageSubFolderName, maxSize
            )
        }
    }

    /**
     * 解析章节缓存文件路径: `{rootPath}/{bookFolderName}/{chapterFileName}`。
     */
    private fun resolveChapterFile(book: Book, chapter: BookChapter): Path {
        return rootDir.resolve(getFolderName(book)).resolve(chapter.getFileName())
    }

    /** 解析书籍缓存目录下任意文件路径 (`.nr` 标记等)。 */
    private fun resolveCacheFile(book: Book, fileName: String): Path {
        return rootDir.resolve(getFolderName(book)).resolve(fileName)
    }

    companion object {
        /**
         * 默认章节缓存根路径, 取 [DataStorageProviders] 的 `chapterCacheDir`
         * (= `{desktopAppRootDir}/book_cache`), 保证与其他存储路径单一事实源。
         */
        fun defaultRootPath(): String {
            return DataStorageProviders.get().chapterCacheDir
        }
    }
}
