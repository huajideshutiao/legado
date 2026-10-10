package io.legado.app.help.book

import io.legado.app.data.entities.Book
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import io.legado.app.utils.File
import io.legado.app.utils.inputStream

/**
 * [LocalBookLocator] iOS/鸿蒙 (Native target) 共用真实实现 (基于 [kotlin.io.File])。
 *
 * # 共用原因
 * iOS 与鸿蒙两端 LocalBookLocator 主体逻辑完全一致 (本地书路径解析 + 路径缓存:
 * getLocalPath/removeLocalPathCache),
 * Kotlin/Native 在 iOS/linuxArm64 (鸿蒙) 上均支持 [kotlin.io.File],
 * 故统一改为 [kotlin.io.File] 实现下沉到 nativeMain 共用, 平台源集用 typealias 别名 + 各自 register 函数。
 *
 * # 路径解析规则 (与 jvmMain [JvmLocalBookLocator] 行为对齐)
 * - bookUrl 形如 `file:///var/mobile/.../book.txt` (iOS) 或 `file:///data/storage/.../book.txt` (鸿蒙)
 *   → 取 URL path 部分 (POSIX 路径)
 * - bookUrl 形如 `/var/mobile/.../book.txt` 或 `/data/storage/.../book.txt` → 直接当本地路径
 * - 网络书源 (`http(s)://...`) → 返回 null
 *
 * # 缓存
 * 用 [Volatile] + [MutableMap] 缓存 bookUrl → 本地路径, 避免重复 URI 解析
 * (Kotlin/Native 无 ConcurrentHashMap, 用 Volatile + synchronized 块;
 * 访问频率低, 不需要高并发优化)。
 *
 * 模式参考 [io.legado.app.help.book.JvmLocalBookLocator]。
 *
 * 文件动作可被子类覆写: iOS/鸿蒙把"能否打开读取"的探测放进各自的目录授权内
 * (见 [isReadableFile])。
 */
open class NativeLocalBookLocator : LocalBookLocator {

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
            saveDir = effectiveBookSaveDir(),
            importDir = effectiveImportBookDir(),
        ) { dir -> searchInDir(dir, book.originName) }
    }

    /**
     * 在一个目录里按名找回。
     *
     * iOS/鸿蒙覆写本方法把整个查找放进该目录的授权内: 容器外目录未经授权时目录列举会得到空结果,
     * 按名找回将无声失效 (见 `IosLocalBookLocator` / `OhosLocalBookLocator`)。
     */
    protected open fun searchInDir(dir: String, originName: String): String? =
        findReadableLocalBookFileInDir(dir, originName, ::isReadableFile)

    /**
     * [path] 能否作为文件打开读取。
     *
     * iOS/鸿蒙覆写本方法把探测放进各自的目录授权内 (容器外路径无授权时直接探测会得出"文件打不开"
     * 的错判), 见 `IosLocalBookLocator` / `OhosLocalBookLocator`。
     */
    protected open fun isReadableFile(path: String): Boolean = canOpenFile(path)

    /**
     * 平台无关的"能打开"探测: 是普通文件且能打开一次。
     *
     * 普通文件判定是必要的收口 —— 指向目录的引用能打开却不能读, 放行后要等阅读层取值才失败。
     */
    protected fun canOpenFile(path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        val stream = runCatching { file.inputStream() }.getOrNull() ?: return false
        runCatching { stream.close() }
        return true
    }

    override fun removeLocalPathCache(book: Book) {
        synchronized(cacheLock) {
            pathCache.remove(book.bookUrl)
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
     * 与 jvmMain [JvmLocalBookLocator] 的 parseLocalPath 行为对齐, 但不依赖 java.net.URI:
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
