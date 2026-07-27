package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.file.AppFilesDirs
import kotlin.io.File

/**
 * [BookStorage] iOS/鸿蒙 (Native target) 共用真实实现 (基于 [kotlin.io.File])。
 *
 * # 共用原因
 * iOS 与鸿蒙两端 BookStorage 主体逻辑完全一致 (章节缓存 I/O: saveText/getContent/hasContent/
 * delContent/clearCache/updateCacheFolder/clearInvalidCache), 仅 iOS 端原用 NSFileManager + NSData,
 * 鸿蒙端用 kotlin.io.File; Kotlin/Native 在 iOS/linuxArm64 (鸿蒙) 上均支持 [kotlin.io.File],
 * 故统一改为 [kotlin.io.File] 实现下沉到 nativeMain 共用, 平台源集用 typealias 别名 + 各自 register 函数。
 *
 * # 目录结构
 * - rootPath: `{AppFilesDirs.filesDir}/book_cache/` (沙盒 / 用户工作目录下持久化)
 * - 单本书缓存: `book_cache/{bookFolderName}/`
 * - 章节文件: `book_cache/{bookFolderName}/{chapterFileName}`
 *   (chapterFileName 由 [BookChapter.getFileName] 派生, 形如 `00001-abc123.nb`)
 *
 * # 行为对齐 jvmMain JvmBookStorage / 原 iOS IosBookStorage / 原 ohos OhosBookStorage
 * - [saveText]: [File.mkdirs] + [File.writeText] (UTF-8)
 * - [getContent]: [File.readText] (UTF-8) + 空字符串视为无内容
 * - [hasContent]: [File.exists]
 * - [delContent]/[clearCache]: [File.deleteRecursively] (递归删除整本书缓存目录)
 * - [getChapterFiles]: [File.listFiles] + 过滤普通文件 (排除子目录)
 * - [updateCacheFolder]: [File.renameTo] (与 JVM Files.move / iOS NSFileManager.moveItemAtPath 等价)
 * - [clearInvalidCache]: 遍历 + 累计大小 + 按修改时间淘汰
 *
 * # 与原 iOS 端差异 (统一为 kotlin.io.File 后)
 * - 原子写: iOS 用 NSData.writeToFile(atomically=true); 统一后用 [File.writeText]
 *   (Kotlin/Native kotlin.io.File 无原子写 API, 与 jvmMain Files.write 行为一致, 已足够)
 * - 文件大小/修改时间: iOS 用 NSFileManager.attributesOfItemAtPath 取 NSFileSize/NSFileModificationDate;
 *   统一后用 [File.length] / [File.lastModified] (返回 epoch millis, 与 iOS NSDate.timeIntervalSince1970*1000 等价)
 * - 普通文件判断: iOS 用 attributesOfItemAtPath 取 NSFileType; 统一后用 [File.isFile]
 *
 * 模式参考 [io.legado.app.help.book.JvmBookStorage] / [io.legado.app.help.config.AppConfigProviders]。
 */
class NativeBookStorage(
    override val rootPath: String = defaultRootPath()
) : BookStorage {

    override fun getFolderName(book: Book): String = book.getFolderName()

    override fun getChapterFiles(book: Book): List<String> {
        val bookDir = resolveBookDir(book)
        val dir = File(bookDir)
        if (!dir.exists()) return emptyList()
        // listFiles 返回目录下所有项的 File 对象 (含子目录), 过滤出普通文件
        // 与 JVM Files.list + Files.isRegularFile / iOS contentsOfDirectoryAtPath + isRegularFile 行为对齐
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile }.map { it.name }
    }

    override fun saveText(book: Book, chapter: BookChapter, text: String) {
        if (text.isEmpty()) return
        val file = File(resolveChapterFile(book, chapter))
        // 父目录不存在则递归创建 (与 Android FileUtils.createFileIfNotExist / iOS createDirectoryAtPath 行为对齐)
        file.parentFile?.mkdirs()
        // 直接 writeText (UTF-8) (kotlin.io.File 无原子写 API, 与 JVM Files.write 行为一致)
        file.writeText(text, Charsets.UTF_8)
    }

    override fun getContent(book: Book, chapter: BookChapter): String? {
        val file = File(resolveChapterFile(book, chapter))
        if (!file.exists()) return null
        // readText (UTF-8) (与 JVM Files.readAllBytes + UTF-8 解码 / iOS NSData→String 行为等价)
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        // 与 Android BookHelp.getContent 行为对齐: 空字符串视为无内容
        return text.ifEmpty { null }
    }

    override fun delContent(book: Book) {
        val bookDir = File(resolveBookDir(book))
        if (bookDir.exists()) {
            // 递归删除整本书缓存目录 (与 Android FileUtils.delete / iOS removeItemAtPath 行为对齐)
            bookDir.deleteRecursively()
        }
    }

    override fun delContent(book: Book, chapter: BookChapter) {
        // 删除单个章节缓存文件 (与 Android BookHelp.delContent 行为对齐)
        // 路径: {rootPath}/{bookFolderName}/{chapterFileName}
        val file = File(resolveChapterFile(book, chapter))
        if (file.exists()) {
            file.delete()
        }
    }

    override fun hasContent(book: Book, chapter: BookChapter): Boolean {
        return File(resolveChapterFile(book, chapter)).exists()
    }

    override fun clearCache() {
        val root = File(rootPath)
        if (root.exists()) {
            root.deleteRecursively()
        }
    }

    override fun clearCache(book: Book) {
        delContent(book)
    }

    override fun updateCacheFolder(oldBook: Book, newBook: Book) {
        // 与 Android BookHelp.updateCacheFolder / iOS updateCacheFolder 行为对齐:
        // 用 getFolderNameNoCache 比较 + rename
        val oldName = oldBook.getFolderNameNoCache()
        val newName = newBook.getFolderNameNoCache()
        if (oldName == newName) return
        val oldDir = File("$rootPath/$oldName")
        if (!oldDir.exists()) return
        val newDir = File("$rootPath/$newName")
        // 确保新目录父目录存在 (与 JVM Files.createDirectories(newDir.parent) 行为对齐)
        newDir.parentFile?.mkdirs()
        // renameTo 替换已存在目标 (与 JVM StandardCopyOption.REPLACE_EXISTING / iOS moveItemAtPath 行为对齐)
        // 注意: kotlin.io.File.renameTo 在目标已存在时行为依赖平台 (POSIX rename 会原子替换)
        oldDir.renameTo(newDir)
    }

    override fun clearInvalidCache(maxSize: Long) {
        val root = File(rootPath)
        if (!root.exists()) return
        // 列出所有书缓存目录 (与 JVM Files.list / iOS contentsOfDirectoryAtPath 行为对齐)
        val bookDirs = root.listFiles()?.filter { it.isDirectory } ?: return
        if (bookDirs.isEmpty()) return

        // 计算每个目录大小 + 最后修改时间 (按目录粒度淘汰, 与 iOS 端 / Android 端行为一致)
        val stats = bookDirs.map { dir ->
            DirStat(dir.absolutePath, dirSize(dir), dir.lastModified())
        }

        var total = stats.sumOf { it.size }
        if (total <= maxSize) return
        // 按修改时间升序淘汰最旧的, 直到总大小 <= maxSize
        // (与 Android sortedBy { it.lastModified() } / iOS stats.sortedBy { it.mtime } 行为对齐)
        stats.sortedBy { it.mtime }.forEach { stat ->
            if (total <= maxSize) return
            File(stat.path).deleteRecursively()
            total -= stat.size
        }
    }

    /**
     * 解析章节缓存文件路径: `{rootPath}/{bookFolderName}/{chapterFileName}`。
     */
    private fun resolveChapterFile(book: Book, chapter: BookChapter): String {
        return "${resolveBookDir(book)}/${chapter.getFileName()}"
    }

    private fun resolveBookDir(book: Book): String {
        return "$rootPath/${getFolderName(book)}"
    }

    /**
     * 计算目录总大小 (字节)。
     *
     * 递归遍历目录下所有普通文件, 累计 [File.length];
     * 与 JVM `dir.toFile().walkTopDown().filter { it.isFile }.sumOf { it.length() }` /
     * iOS dirStat 递归行为对齐。
     */
    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var size = 0L
        dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size
    }

    companion object {
        /**
         * 默认章节缓存根路径: `{AppFilesDirs.filesDir}/book_cache`。
         *
         * - iOS 沙盒 Documents 目录 / 鸿蒙应用沙盒 filesDir (持久化)
         * - 与桌面端 `~/.legado/book_cache` 行为等价 (持久化 + 用户可访问)
         * - 路径分隔符恒为 "/" (POSIX 文件系统)
         */
        fun defaultRootPath(): String {
            val filesDir = AppFilesDirs.get().filesDir
            return if (filesDir.endsWith("/")) "${filesDir}book_cache" else "$filesDir/book_cache"
        }
    }
}

/**
 * [clearInvalidCache] 内部目录统计三元组 (路径 / 大小 / 最后修改时间)。
 *
 * 单独提为顶层 private data class, 与 [io.legado.app.help.book.JvmBookStorage.DirStat] 一致,
 * 避免 local data class 在某些 Kotlin 版本 / KSP 处理上的边界问题。
 */
private data class DirStat(val path: String, val size: Long, val mtime: Long)

/**
 * 注册 [NativeBookStorage] 到 [BookStorageProviders]
 * (iOS / 鸿蒙共用, 宿主启动早期调用, 在 AppFilesDirs 就绪之后)。
 */
fun registerNativeBookStorage() {
    BookStorageProviders.register(NativeBookStorage())
}
