package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.file.desktopAppRootDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors

/**
 * [BookStorage] 桌面 JVM 实现。
 *
 * 章节缓存目录结构: `~/.legado/book_cache/{bookFolderName}/{chapterFileName}`
 * - `~` 解析为 `System.getProperty("user.home")`, 与桌面端数据库路径
 *   (`~/.legado/legado.db`, 见 [io.legado.app.data.BundledDatabaseDriver]) 同源
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
        if (!Files.isDirectory(rootDir)) return
        // 列出所有书缓存目录, 按最后修改时间排序, 累计大小超 maxSize 时淘汰最旧的
        // 对应 Android BookHelp.clearInvalidCache 中"漫画图片缓存管理 (512MB)"段
        val bookDirs = Files.list(rootDir).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .collect(Collectors.toList())
        }
        if (bookDirs.isEmpty()) return

        // 计算每个目录大小 + 最后修改时间 (按目录粒度淘汰, 与 Android 端行为一致)
        val stats = bookDirs.map { dir ->
            val size = dir.toFile().walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val mtime = try {
                Files.getLastModifiedTime(dir).toMillis()
            } catch (e: Exception) {
                0L
            }
            DirStat(dir, size, mtime)
        }

        var total = stats.sumOf { it.size }
        if (total <= maxSize) return
        // 按修改时间升序淘汰最旧的, 直到总大小 <= maxSize (与 Android sortedBy { it.lastModified() } 行为对齐)
        stats.sortedBy { it.mtime }.forEach { stat ->
            if (total <= maxSize) return
            stat.path.toFile().deleteRecursively()
            total -= stat.size
        }
    }

    override fun clearInvalidBookFolders(
        validFolderNames: Set<String>,
        imageSubFolderName: String,
        maxSize: Long
    ) {
        // 桌面端暂仅做超量淘汰 (复用 clearInvalidCache), 删除不在书架文件夹逻辑待后续实现
        clearInvalidCache(maxSize)
    }

    /**
     * 解析章节缓存文件路径: `{rootPath}/{bookFolderName}/{chapterFileName}`。
     */
    private fun resolveChapterFile(book: Book, chapter: BookChapter): Path {
        return rootDir.resolve(getFolderName(book)).resolve(chapter.getFileName())
    }

    companion object {
        /**
         * 默认章节缓存根路径: `{desktopAppRootDir}/book_cache`。
         *
         * - 便携模式 (jpackage 打包后): 跟随 exe 同级 `data/book_cache` (Main.kt 设 legado.portable.root)
         * - 开发模式: `~/.legado/book_cache` (避免污染项目源码树)
         * - 与桌面端数据库路径同源, 集中在应用根目录下管理
         */
        fun defaultRootPath(): String {
            return Paths.get(desktopAppRootDir(), "book_cache").toString()
        }
    }
}

/**
 * [clearInvalidCache] 内部目录统计三元组 (路径 / 大小 / 最后修改时间)。
 *
 * 单独提为顶层 private data class, 避免 local data class 在某些 Kotlin 版本 /
 * KSP 处理上的边界问题, 同时便于扩展。
 */
private data class DirStat(val path: Path, val size: Long, val mtime: Long)
