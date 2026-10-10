package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.help.file.desktopResolveStoredRef
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [LocalBookLocator] 桌面 JVM 实现。
 *
 * 本地书路径解析规则:
 * - bookUrl 形如 `file:///C:/path/book.txt` → 取 URI.path 后转 Path
 * - bookUrl 形如 `/path/book.txt` 或 `C:\path\book.txt` → 直接当本地路径
 * - 网络书源 (`http(s)://...`) → 返回 null
 *
 * 缓存: 用 [ConcurrentHashMap] 缓存 bookUrl → 本地路径, 避免重复 URI 解析。
 *
 * 注册: desktop 模块启动时通过 [LocalBookLocators.register] 注入。
 */
class JvmLocalBookLocator : LocalBookLocator {

    /** bookUrl → 本地路径缓存 (避免重复 URI 解析)。 */
    private val pathCache = ConcurrentHashMap<String, String>()

    override fun getLocalPath(book: Book): String? {
        if (!book.isLocal) return null
        pathCache[book.bookUrl]?.let { return it }
        val path = parseLocalPath(book.bookUrl) ?: return null
        pathCache[book.bookUrl] = path
        return path
    }

    /**
     * 取本地书当前可用的文件引用: bookUrl 解析出的文件打不开时, 在"书籍保存位置"与"导入目录"里
     * 按 [Book.originName] 找回同名文件 (原版 `Book.getLocalUri()` 的定位口径: 每个目录下探 5 层、
     * 最多列举 100 个目录, 两个目录是同一处时只查一次)。
     *
     * 判据是"能打开": 打不开的引用 (含存在但读不了的文件) 同样进入按名找回。代价是每次判定多一次
     * 打开探测 —— 对引用自身与每个同名候选各开一次再关掉, 调用方随后的读取再开一次。
     *
     * 只服务本次读取: 不写路径缓存, 免得其它入口 (如删除) 把按名找到的文件
     * 当成书籍自身的目标。找不到返回 null。
     */
    override fun getReadableFileRef(book: Book): String? {
        val path = getLocalPath(book) ?: return null
        if (isReadableFile(path)) return path
        return findLocalBookFileInDirs(
            // 目录标识可能是 file: URI 或数据根下相对引用, 统一按存储引用解析
            saveDir = effectiveBookSaveDir()?.let { desktopResolveStoredRef(it).absolutePath },
            importDir = effectiveImportBookDir()?.let { desktopResolveStoredRef(it).absolutePath },
        ) { dir ->
            findReadableLocalBookFileInDir(dir, book.originName, ::isReadableFile)
        }
    }

    /**
     * [path] 能否作为文件打开: 是普通文件且能打开一次。
     *
     * 普通文件判定是必要的收口 —— 指向目录的引用能打开却不能读, 放行后要等阅读层取值才失败。
     */
    private fun isReadableFile(path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        val stream = runCatching { file.inputStream() }.getOrNull() ?: return false
        runCatching { stream.close() }
        return true
    }

    override fun removeLocalPathCache(book: Book) {
        pathCache.remove(book.bookUrl)
    }

    /**
     * 解析 bookUrl 为本地文件路径 (委托 [desktopResolveStoredRef], 缓存的是解析后的绝对路径)。
     *
     * - `books/x.epub` (数据根下相对引用, saveBookFile 新格式) → `{数据根}/books/x.epub`
     * - `file:///C:/path/book.txt` → `C:\path\book.txt` (URI 解码 + 平台分隔符, 旧数据)
     * - `/path/book.txt`、`C:\path\book.txt` → 直接当本地路径 (旧数据)
     * - `http(s)://...` → null (网络书源, 非本地)
     */
    private fun parseLocalPath(bookUrl: String): String? {
        if (bookUrl.startsWith("http:") || bookUrl.startsWith("https:")) return null
        return try {
            desktopResolveStoredRef(bookUrl).absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
