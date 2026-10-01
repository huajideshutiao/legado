@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package io.legado.app.utils

import io.legado.app.exception.SecurityException
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.BooleanVarOf
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import okio.IOException
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorWritingForDeleting
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkCreationMinimalBookmark
import platform.Foundation.NSURLBookmarkResolutionWithoutUI
import platform.Foundation.NSUserDefaults
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create

/**
 * iOS 用户授权目录的 security-scoped 授权表 (minimal bookmark 持久化 + 逐次访问租约)。
 *
 * # 授权契约 (Apple《Providing access to directories》)
 * - 系统"文件"面板选出的目录是 security-scoped URL: 访问前
 *   [NSURL.startAccessingSecurityScopedResource], 访问结束对**同一对象**配对 stop;
 * - 长期引用用 [NSURLBookmarkCreationMinimalBookmark] 存 [NSUserDefaults], 再次访问解析回
 *   security-scoped URL; bookmark 陈旧时在有效 scope 内重建后覆盖存储;
 * - 授权是**目录集合**而非单个"最后目录": 用户为不同书籍分别授权过的目录都保留, 否则找回第二本书
 *   会让第一本书失去授权;
 * - 每次访问独立取租约 ([acquireLease]) 并在 [IosSecurityScopeLease.close] 释放, 不把授权挂到进程生命周期;
 * - 根 URL start 返回 false = 授权已被撤销/失效 → 抛 [SecurityException], 由阅读层提示重新选目录;
 *   根 scope 生效时其内容由该目录 URL 递归覆盖 (Apple: 目录 URL 可递归访问其全部内容)。
 *
 * 本对象只负责授权与 [NSFileCoordinator] 协调, 不做文件读写: 读写仍由 [File] (okio) 唯一实现。
 * 路径判定一律先规范化 (realpath 语义): 含 `..`/符号链接的路径按解析后的真实位置判归属,
 * 授权目录内的符号链接越界到目录外时不予授权 ([withAccess] 协调的是被访问文件自身,
 * 租约持有的是其所属授权根)。
 */
object IosSecurityScopedStorage {

    /** 书籍目录 bookmark 集合的 NSUserDefaults key (值为 base64 bookmark 字符串数组)。 */
    private const val BOOK_TREE_BOOKMARKS_KEY = "iosBookTreeBookmarks"

    /** 导入根目录 bookmark 的 NSUserDefaults key (单值: 导入浏览器只服务一个根目录)。 */
    private const val IMPORT_ROOT_BOOKMARK_KEY = "iosImportRootBookmark"

    private val lock = SynchronizedObject()

    private val defaults: NSUserDefaults get() = NSUserDefaults.standardUserDefaults

    /** 已解析的授权根: security-scoped URL + 规范化路径 (包含关系判定用)。 */
    private class AuthorizedRoot(val url: NSURL, val canonicalPath: String)

    /** 已解析的授权根缓存; 集合变更或解析失败时整体重载。 */
    @Volatile
    private var roots: List<AuthorizedRoot> = emptyList()

    /** 导入根 bookmark 的已解析授权根缓存 (不持有 scope, 仅避免重复解析)。 */
    @Volatile
    private var importRoot: AuthorizedRoot? = null

    // ===== 路径判定 =====

    /** 规范化 POSIX 路径 (realpath 语义, 解析符号链接; 目标不存在时回落原样)。 */
    private fun canonicalPath(path: String): String =
        File(path).absolutePath.toPath(normalize = true).toString()

    /** 路径是否位于应用自身容器 (沙盒 home / bundle) 内 —— 这类路径无需 security scope。 */
    private fun isSandboxPath(canonicalTarget: String): Boolean =
        underRoot(canonicalPath(NSHomeDirectory()), canonicalTarget) ||
            underRoot(canonicalPath(NSBundle.mainBundle.bundlePath), canonicalTarget)

    private fun underRoot(root: String, path: String): Boolean {
        val prefix = root.trimEnd('/')
        return path == prefix || path.startsWith("$prefix/")
    }

    // ===== 授权集合 =====

    /**
     * 记录用户新选的书籍目录: 起 scope → 建 minimal bookmark → 并入集合 → 停 scope。
     *
     * 同一目录重复选择时替换旧条目 (按规范化路径判身份), 集合内其余目录不受影响。
     *
     * @return 目录 POSIX 路径; 建 bookmark 失败返回 null (调用方按"未授权"处理, 不落任何存储)
     */
    fun addBookTree(url: NSURL): String? {
        val path = url.path ?: return null
        val started = url.startAccessingSecurityScopedResource()
        if (!started && !isSandboxPath(canonicalPath(path))) return null
        val bookmark = try {
            bookmarkString(url)
        } finally {
            if (started) url.stopAccessingSecurityScopedResource()
        } ?: return null
        // 同一目录重复选择时替换旧条目; 集合内其余目录一律保留 —— 否则找回第二本
        // 不同目录的书会让第一本书失去授权
        val target = canonicalPath(path)
        synchronized(lock) {
            val kept = storedBookmarks().filterNot { existing ->
                resolveBookmark(existing)?.first?.path?.let { canonicalPath(it) } == target
            }
            storeBookmarks(kept + bookmark)
            roots = emptyList()
        }
        return path
    }

    private fun storedBookmarks(): List<String> =
        defaults.arrayForKey(BOOK_TREE_BOOKMARKS_KEY)
            ?.filterIsInstance<String>()
            .orEmpty()

    private fun storeBookmarks(bookmarks: List<String>) {
        defaults.setObject(bookmarks, forKey = BOOK_TREE_BOOKMARKS_KEY)
        defaults.synchronize()
    }

    /**
     * 解析全部已存 bookmark 为授权根集合。
     *
     * 陈旧 bookmark 在有效 scope 内重建；暂时无法重建时保留原值。
     * 无法解析的条目移除，其余目录不受影响。
     */
    private fun resolvedRoots(): List<AuthorizedRoot> = synchronized(lock) {
        if (roots.isNotEmpty()) return@synchronized roots
        val stored = storedBookmarks()
        if (stored.isEmpty()) return@synchronized emptyList()
        val parsed = mutableListOf<AuthorizedRoot>()
        val kept = mutableListOf<String>()
        for (raw in stored) {
            val resolved = resolveBookmark(raw) ?: continue
            val url = resolved.first
            val rawPath = url.path ?: continue
            val bookmark = if (resolved.second) renewBookmark(url) ?: raw else raw
            kept += bookmark
            parsed += AuthorizedRoot(url, canonicalPath(rawPath))
        }
        if (kept != stored) storeBookmarks(kept)
        roots = parsed
        parsed
    }

    /** 在有效 scope 内重建陈旧 bookmark; scope 起不来 (授权已撤销) 返回 null。 */
    private fun renewBookmark(url: NSURL): String? {
        if (!url.startAccessingSecurityScopedResource()) return null
        return try {
            bookmarkString(url)
        } finally {
            url.stopAccessingSecurityScopedResource()
        }
    }

    /** 丢弃全部授权缓存 (含导入根): 授权可能已在系统设置里被改动。 */
    private fun invalidateAll() {
        synchronized(lock) {
            roots = emptyList()
            importRoot = null
        }
    }

    // ===== 导入根目录 =====

    /**
     * 记录用户新选的导入根目录 (存 bookmark 供 [restoreImportRootPath] 下次恢复)。
     * 不在此持有 scope: 导入浏览的目录列举/读取与书籍读取一样走 [acquireLease] 逐次授权。
     *
     * @return 目录 POSIX 路径; 建 bookmark 失败返回 null
     */
    fun setImportRoot(url: NSURL): String? {
        val path = url.path ?: return null
        val started = url.startAccessingSecurityScopedResource()
        if (!started && !isSandboxPath(canonicalPath(path))) return null
        val bookmark = try {
            bookmarkString(url)
        } finally {
            if (started) url.stopAccessingSecurityScopedResource()
        } ?: return null
        defaults.setObject(bookmark, forKey = IMPORT_ROOT_BOOKMARK_KEY)
        defaults.synchronize()
        synchronized(lock) { importRoot = AuthorizedRoot(url, canonicalPath(path)) }
        return path
    }

    /**
     * 恢复上次导入根目录: 解析 bookmark 取回 security-scoped URL 并立即取一次租约验证授权仍有效,
     * 验证完即释放 (不留长期持有)。
     *
     * @return 目录 POSIX 路径; 无存储/授权已失效返回 null
     */
    fun restoreImportRootPath(): String? {
        val root = synchronized(lock) { importRoot } ?: run {
            val raw = defaults.stringForKey(IMPORT_ROOT_BOOKMARK_KEY) ?: return null
            val resolved = resolveBookmark(raw) ?: return null
            val url = resolved.first
            val rawPath = url.path ?: return null
            if (resolved.second) {
                renewBookmark(url)?.let {
                    defaults.setObject(it, forKey = IMPORT_ROOT_BOOKMARK_KEY)
                    defaults.synchronize()
                }
            }
            val restored = AuthorizedRoot(url, canonicalPath(rawPath))
            synchronized(lock) { importRoot = restored }
            restored
        }
        val path = root.url.path ?: return null
        return runCatching {
            acquireLease(path).close()
            path
        }.getOrNull()
    }

    // ===== 访问租约 =====

    /**
     * 取 [path] 的授权租约; 调用方必须 [IosSecurityScopeLease.close] 释放。
     *
     * - 沙盒内路径: 返回无需 scope 的空租约;
     * - 落在已授权目录下: 对该根 URL start, 租约持有同一 URL 并在 close 时配对 stop;
     * - 其余情况抛 [SecurityException] (阅读层据此提示"选择书籍所在文件夹")。
     */
    fun acquireLease(path: String): IosSecurityScopeLease {
        val target = canonicalPath(path)
        if (isSandboxPath(target)) return IosSecurityScopeLease.none()
        val root = authorizedRootFor(target) ?: throw SecurityException("没有访问权限: $path")
        if (!root.startAccessingSecurityScopedResource()) {
            invalidateAll()
            throw SecurityException("没有访问权限: $path")
        }
        val lease = IosSecurityScopeLease(root, started = true)
        try {
            val rootPath = root.path ?: throw SecurityException("授权目录没有有效路径")
            if (!underRoot(canonicalPath(rootPath), canonicalPath(path))) {
                throw SecurityException("文件位于授权目录之外: $path")
            }
            return lease
        } catch (e: Throwable) {
            lease.close()
            throw e
        }
    }

    /** 找规范化路径 [canonicalTarget] 所属的授权根 (书籍目录集合优先, 其次导入根); 无匹配返回 null。 */
    private fun authorizedRootFor(canonicalTarget: String): NSURL? {
        resolvedRoots().firstOrNull { underRoot(it.canonicalPath, canonicalTarget) }
            ?.let { return it.url }
        return synchronized(lock) { importRoot }
            ?.takeIf { underRoot(it.canonicalPath, canonicalTarget) }
            ?.url
    }

    /**
     * 在授权范围内访问 [path]: 取租约 → [NSFileCoordinator] 协调被访问文件自身 → 释放租约。
     *
     * [block] 收到 [path] 的规范化路径 (协调器也可能传入替代路径, 如文件被移动后的新位置)。
     * 沙盒内路径 (租约不持 scope) 不经协调器, 直接执行。
     * 协调器报错或协调块未执行时抛 [IOException]；授权失败由 [acquireLease] 抛出。
     *
     * @param write true 走 `coordinateWritingItemAtURL` (删除等写操作)
     */
    fun <T> withAccess(path: String, write: Boolean = false, block: (String) -> T): T {
        val lease = acquireLease(path)
        try {
            val target = canonicalPath(path)
            if (!lease.holdsScope) return block(target)
            return coordinate(target, write, block)
        } finally {
            lease.close()
        }
    }

    /**
     * 在已授权目录里按文件名找文件 (对照原版 `FileDoc.find(name)` 默认 depth=0: 只查所选目录)。
     *
     * @param dirUri 用户刚选定的目录路径 (与某个授权根同路径或其子目录)
     * @return 找到时返回 `file://` + 绝对路径 (与导入写入的 bookUrl 同格式), 否则 null
     */
    fun findBookFileInTree(dirUri: String, fileName: String): String? {
        if (fileName.isEmpty()) return null
        val target = canonicalPath(dirUri)
        if (!isSandboxPath(target) && authorizedRootFor(target) == null) return null
        return withAccess(dirUri) { dirPath ->
            val dir = File(dirPath)
            val direct = File(dir, fileName)
            if (direct.isFile) "file://${direct.absolutePath}" else null
        }
    }

    // ===== 内部实现 =====

    /** 在 [NSFileCoordinator] 协调块内执行 [block]; 协调失败抛异常而非返回空值。 */
    private fun <T> coordinate(
        target: String,
        write: Boolean,
        block: (String) -> T,
    ): T {
        val url = NSURL.fileURLWithPath(target)
        var result: T? = null
        var executed = false
        var failure: Throwable? = null
        val accessor: (NSURL?) -> Unit = { coordinated ->
            executed = true
            try {
                result = block(coordinated?.path ?: target)
            } catch (e: Throwable) {
                failure = e
            }
        }
        val coordinator = NSFileCoordinator()
        val error = memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            if (write) {
                coordinator.coordinateWritingItemAtURL(
                    url, NSFileCoordinatorWritingForDeleting, err.ptr, accessor,
                )
            } else {
                coordinator.coordinateReadingItemAtURL(url, 0uL, err.ptr, accessor)
            }
            err.value
        }
        failure?.let { throw it }
        if (error != null) {
            throw IOException("文件协调失败: ${error.localizedDescription}")
        }
        if (!executed) {
            throw IOException("文件协调未执行: $target")
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** URL → minimal bookmark 的 Base64 字符串; 失败返回 null。 */
    private fun bookmarkString(url: NSURL): String? = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        url.bookmarkDataWithOptions(
            options = NSURLBookmarkCreationMinimalBookmark,
            includingResourceValuesForKeys = null,
            relativeToURL = null,
            error = err.ptr,
        )
    }?.base64EncodedStringWithOptions(0u)

    /** Base64 bookmark → (URL, 是否陈旧); 解析失败返回 null。 */
    private fun resolveBookmark(encoded: String): Pair<NSURL, Boolean>? {
        val data = NSData.create(base64EncodedString = encoded, options = 0u) ?: return null
        return memScoped {
            val stale = alloc<BooleanVarOf<Boolean>>()
            val err = alloc<ObjCObjectVar<NSError?>>()
            val url = NSURL.URLByResolvingBookmarkData(
                bookmarkData = data,
                options = NSURLBookmarkResolutionWithoutUI,
                relativeToURL = null,
                bookmarkDataIsStale = stale.ptr,
                error = err.ptr,
            )
            url?.let { it to stale.value }
        }
    }
}

/**
 * security-scoped 授权租约: 持有实际 start 过的授权根 URL, [close] 对**同一 URL** 配对 stop。
 *
 * 沙盒内路径无需 scope, [none] 返回不持有任何 URL 的空租约 (close 为 no-op)。
 */
class IosSecurityScopeLease internal constructor(
    private val url: NSURL?,
    private val started: Boolean,
) : AutoCloseable {

    /** 租约是否实际持有 security scope (空租约为 false)。 */
    internal val holdsScope: Boolean get() = started

    private val closed = atomic(false)

    override fun close() {
        if (closed.compareAndSet(expect = false, update = true) && started) {
            url?.stopAccessingSecurityScopedResource()
        }
    }

    internal companion object {
        fun none(): IosSecurityScopeLease = IosSecurityScopeLease(null, started = false)
    }
}
