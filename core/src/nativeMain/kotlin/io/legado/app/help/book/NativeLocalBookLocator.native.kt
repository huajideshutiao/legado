package io.legado.app.help.book

import io.legado.app.data.entities.Book
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import io.legado.app.utils.File

/**
 * [LocalBookLocator] iOS/鸿蒙 (Native target) 共用真实实现 (基于 [kotlin.io.File])。
 *
 * # 共用原因
 * iOS 与鸿蒙两端 LocalBookLocator 主体逻辑完全一致 (本地书路径解析 + 文件操作:
 * getLocalPath/getArchivePath/cacheLocalPath/removeLocalPathCache/getLastModified/deleteBook),
 * 仅 iOS 端原用 NSFileManager, 鸿蒙端用 kotlin.io.File;
 * Kotlin/Native 在 iOS/linuxArm64 (鸿蒙) 上均支持 [kotlin.io.File],
 * 故统一改为 [kotlin.io.File] 实现下沉到 nativeMain 共用, 平台源集用 typealias 别名 + 各自 register 函数。
 *
 * # 路径解析规则 (与 jvmMain JvmLocalBookLocator / 原 iOS IosLocalBookLocator / 原 ohos OhosLocalBookLocator 行为对齐)
 * - bookUrl 形如 `file:///var/mobile/.../book.txt` (iOS) 或 `file:///data/storage/.../book.txt` (鸿蒙)
 *   → 取 URL path 部分 (POSIX 路径)
 * - bookUrl 形如 `/var/mobile/.../book.txt` 或 `/data/storage/.../book.txt` → 直接当本地路径
 * - 网络书源 (`http(s)://...`) → 返回 null
 *
 * # 压缩包子书路径
 * 从 book.origin 提取 (形如 `local_book::archive.rar`), 由 [Book.archiveName] 扩展提供
 * (BookExtensionsShared.kt, commonMain 已下沉)。
 *
 * # 缓存
 * 用 [Volatile] + [MutableMap] 缓存 bookUrl → 本地路径, 避免重复 URI 解析
 * (与 jvmMain JvmLocalBookLocator 行为对齐; Kotlin/Native 无 ConcurrentHashMap, 用 Volatile 替代)。
 *
 * # 与原 iOS 端差异 (统一为 kotlin.io.File 后)
 * - 文件操作: iOS 用 NSFileManager.attributesOfItemAtPath (NSFileModificationDate) / removeItemAtPath;
 *   统一后用 [File.lastModified] (返回 epoch millis, 与 iOS NSDate.timeIntervalSince1970*1000 等价) /
 *   [File.delete] / [File.deleteRecursively]
 * - deleteBook 语义: 原 iOS 端文件不存在时 removeItemAtPath 返回 false (删除失败);
 *   统一后采用鸿蒙端语义 (文件不存在视为删除成功, 更合理: 已不在即已删除)
 * - deleteBook 分支: 统一后保留鸿蒙端的 isDirectory 分支 (异常场景 bookUrl 指向目录时用 deleteRecursively 兜底)
 * - 并发 Map: 与两端一致, 用 [Volatile MutableMap] + synchronized 块
 *   (LocalBookLocator 访问频率低, 不需要高并发优化)
 *
 * 模式参考 [io.legado.app.help.book.JvmLocalBookLocator]。
 */
class NativeLocalBookLocator : LocalBookLocator {

    /** bookUrl → 本地路径缓存 (避免重复 URI 解析); Kotlin/Native 无 ConcurrentHashMap, 用同步块保护。 */
    @Volatile
    private var pathCache: MutableMap<String, String> = mutableMapOf()

    private val cacheLock = SynchronizedObject()

    override fun getLocalPath(book: Book): String? {
        if (!book.isLocal) return null
        synchronized(cacheLock) {
            pathCache[book.bookUrl]?.let { return it }
        }
        val path = parseLocalPath(book.bookUrl) ?: return null
        synchronized(cacheLock) {
            pathCache[book.bookUrl] = path
        }
        return path
    }

    override fun getArchivePath(book: Book): String? {
        if (!book.isArchive) return null
        // archiveName 扩展已校验 isArchive, 直接取
        // runCatching 防御: archiveName 在非 archive 场景抛 NoStackTraceException, 此处虽已校验仍兜底
        return runCatching { book.archiveName }.getOrNull()
    }

    override fun cacheLocalPath(book: Book, path: String) {
        synchronized(cacheLock) {
            pathCache[book.bookUrl] = path
        }
    }

    override fun removeLocalPathCache(book: Book) {
        synchronized(cacheLock) {
            pathCache.remove(book.bookUrl)
        }
    }

    override fun getLastModified(book: Book): Long {
        val path = getLocalPath(book) ?: return 0L
        return try {
            val file = File(path)
            // lastModified 返回 epoch millis (与 JVM Files.getLastModifiedTime.toMillis() /
            // iOS NSDate.timeIntervalSince1970*1000 等价); 文件不存在返回 0L
            if (!file.exists()) 0L else file.lastModified()
        } catch (e: Exception) {
            0L
        }
    }

    override fun deleteBook(book: Book): Boolean {
        val path = getLocalPath(book) ?: return false
        return try {
            val file = File(path)
            if (!file.exists()) {
                // 文件不存在视为删除成功 (与原 iOS removeItemAtPath 返回 false 不同,
                // 但语义更合理: 已不在即已删除), 同时清理缓存
                removeLocalPathCache(book)
                return true
            }
            // 单文件用 delete(); 若 bookUrl 指向目录 (异常场景) 用 deleteRecursively 兜底
            val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (ok) {
                removeLocalPathCache(book)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 解析 bookUrl 为本地文件路径。
     *
     * - `file:///var/mobile/.../book.txt` (iOS) 或 `file:///data/storage/.../book.txt` (鸿蒙)
     *   → 剥离 file:// scheme 后的路径
     * - `/var/mobile/.../book.txt` 或 `/data/storage/.../book.txt` → 直接当本地路径
     * - `http(s)://...` → null (网络书源, 非本地)
     *
     * 与 jvmMain JvmLocalBookLocator.parseLocalPath / 原 iOS IosLocalBookLocator.parseLocalPath
     * 行为对齐, 但不依赖 java.net.URI:
     * file URL 形态较统一 (file:// + POSIX 路径), 直接字符串剥离即可。
     */
    private fun parseLocalPath(bookUrl: String): String? {
        return try {
            when {
                bookUrl.startsWith("file:") -> {
                    // 剥离 "file://" 前缀, 保留路径部分 (与 java.net.URI.path 行为等价)
                    // 形如 "file:///var/mobile/.../book.txt" -> "/var/mobile/.../book.txt"
                    // 形如 "file://localhost/var/..." -> "/var/..." (省略 host 时)
                    val afterScheme = bookUrl.substringAfter("file://")
                    // 若有 host 段 (如 localhost), 第一个 / 之前是 host, 之后是 path
                    val slashIdx = afterScheme.indexOf('/')
                    if (slashIdx > 0) afterScheme.substring(slashIdx) else afterScheme
                }
                bookUrl.startsWith("http:") || bookUrl.startsWith("https:") -> {
                    null
                }
                else -> {
                    // 直接当本地路径 (/var/mobile/... 或 /data/storage/... 或沙盒绝对路径)
                    bookUrl
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 注册 [NativeLocalBookLocator] 到 [LocalBookLocators]
 * (iOS / 鸿蒙共用, 宿主启动早期调用)。
 */
fun registerNativeLocalBookLocator() {
    LocalBookLocators.register(NativeLocalBookLocator())
}
