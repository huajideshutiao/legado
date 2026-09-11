package io.legado.app.help.image

import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 网络图片字节缓存 (进程内 LRU + 磁盘), 对照原版 Glide 磁盘缓存语义。
 *
 * 原版 (origin/quickjs) PhotoDialog/封面链路: Glide `DiskCacheStrategy.DATA` 缓存 fetcher
 * 输出流 (= OkHttpStreamFetcher 解密后的字节), PhotoDialog.loadByGlide 先
 * `signature("covers").onlyRetrieveFromCache(true)` 命中磁盘再发网络请求;
 * 同一 URL 二次打开直接命中, 不重复下载/解密 (缓存复用)。
 *
 * 本缓存补齐 desktop/鸿蒙 `ImageBitmapLoader` 直连下载路径的
 * "磁盘缓存优先 + 同一 URL 结果复用" (iOS 走 Coil3 自带磁盘缓存, app 端走 Coil3
 * MultiDiskCache, 均不经过本类; android 端 shared ImageBitmapLoader 为旁路, 一并补齐)。
 *
 * - 内存: 32 条 LRU (对齐原 CoverDecodeFetcher decodedBytesCache 语义, 该类已由
 *   SourceDecodeCacheStrategy 解密下沉替代)
 * - 磁盘: `{cachePath}/image_cache/{md5(url或origin+url)}_{isCover}`, 存**解密后**字节
 *   (对齐原版 Glide DATA 缓存的是 fetcher 输出流 = 解密后字节; key 区分封面/正文规则,
 *   避免同一 url 两种规则互相污染 —— 原版 Glide 缓存 key 不区分, 此处语义更严格)
 * - 书源维度: key 含 sourceOrigin, 不同书源同 URL 互不污染 (无源裸 GET 的字节不得
 *   命中带书源解密链路, 反之亦然; 换源/无源→有源切换后重新下载解密); origin 为空时
 *   key 与旧版完全一致, 磁盘旧缓存文件继续命中
 * - 失败结果 (null) 不入缓存; 磁盘不可用时静默降级为纯内存缓存
 *
 * `persistent=true` 时磁盘层路由到封面持久区 (bookCoverCacheDir 下 image_cache_p 子目录,
 * 子目录隔离避免误触同目录 Coil MultiDiskCache 持久区的 journal 与 prune 清理; 系统清缓存
 * 清不掉, 对齐 Coil 端 #covers 持久区语义; 目录取不到时回退临时目录, 文件名加 "p"
 * 后缀防同名互覆); 内存层共享。默认 false, 既有调用点行为不变。
 */
internal object ImageBytesCache {

    private const val MAX_MEMORY_ENTRIES = 32

    /** 磁盘文件数上限, 超限时按文件名排序淘汰一半 (md5 文件名均匀分布, 等效随机淘汰)。 */
    private const val MAX_DISK_FILES = 1000

    private val mutex = Mutex()

    // 无 accessOrder 构造 (native 无 (Int, Float, Boolean) 重载), 手动维护 LRU:
    // get/put 时 remove+put 刷新访问序, 首元素即最久未用 (与 JVM accessOrder=true 等效)
    private val memory = LinkedHashMap<String, ByteArray>()

    /** 书源维度 key (origin 为空时与旧版格式一致, 无源缓存不受维度改动影响)。 */
    private fun cacheKey(url: String, origin: String?, isCover: Boolean): String =
        if (origin.isNullOrEmpty()) "$url\u0000$isCover" else "$url\u0000$origin\u0000$isCover"

    private fun diskFileName(url: String, origin: String?, isCover: Boolean, persistent: Boolean): String {
        val key = if (origin.isNullOrEmpty()) url else "$origin\u0000$url"
        // 持久文件名加 "p" 后缀: 目录取不到回退临时目录时与临时文件区分, 防同名互覆
        val suffix = if (persistent) "${isCover}p" else "$isCover"
        return "${MD5Utils.md5Encode(key)}_$suffix"
    }

    private fun diskDir(persistent: Boolean): String {
        if (persistent) {
            // runCatching 对齐 CoverDiskCache.buildImageDiskCache: bookCoverCacheDir 默认实现
            // 内部 AppFilesDirs.get() 未注册时抛 IllegalStateException, 不能从 get/put 冒出
            val coverDir = runCatching {
                DataStorageProviders.getOrNull()?.bookCoverCacheDir
            }.getOrNull()
            if (coverDir != null) {
                // 落 covers/image_cache_p 子目录而非 covers 根: 该目录同时是 Coil MultiDiskCache
                // 持久区, 同目录写散文件会让本类 prune 按文件名删一半时误删 Coil journal/条目
                return FileUtilsCommon.getPath(coverDir, "image_cache_p")
            }
        }
        return FileUtilsCommon.getPath(FileUtilsCommon.getCachePath(), "image_cache")
    }

    suspend fun get(
        url: String,
        origin: String?,
        isCover: Boolean,
        persistent: Boolean = false,
    ): ByteArray? {
        val k = cacheKey(url, origin, isCover)
        mutex.withLock {
            // remove+put 刷新访问序 (手写 LRU, 见 memory 注释)
            memory.remove(k)?.let { value ->
                memory[k] = value
                return value
            }
        }
        val path = FileUtilsCommon.getPath(diskDir(persistent), diskFileName(url, origin, isCover, persistent))
        val bytes = FileUtilsCommon.readBytes(path)?.takeIf { it.isNotEmpty() } ?: return null
        mutex.withLock {
            memory[k] = bytes
        }
        return bytes
    }

    suspend fun put(
        url: String,
        origin: String?,
        isCover: Boolean,
        bytes: ByteArray,
        persistent: Boolean = false,
    ) {
        if (bytes.isEmpty()) return
        val k = cacheKey(url, origin, isCover)
        mutex.withLock {
            memory.remove(k)
            memory[k] = bytes
            while (memory.size > MAX_MEMORY_ENTRIES) {
                memory.remove(memory.entries.first().key)
            }
        }
        val dir = diskDir(persistent)
        if (!FileUtilsCommon.createFolderIfNotExist(dir)) return
        if (!FileUtilsCommon.writeBytes(
                FileUtilsCommon.getPath(dir, diskFileName(url, origin, isCover, persistent)),
                bytes
            )
        ) return
        pruneIfDiskOverflow(dir)
    }

    /** 磁盘文件数超限时按文件名排序删掉一半 (对照原版 Glide 磁盘缓存 maxSize 清理语义)。 */
    private fun pruneIfDiskOverflow(dir: String) {
        val files = FileUtilsCommon.listFiles(dir)
        if (files.size <= MAX_DISK_FILES) return
        val toDelete = files.sorted().take(files.size / 2)
        toDelete.forEach { FileUtilsCommon.delete(it) }
    }
}
