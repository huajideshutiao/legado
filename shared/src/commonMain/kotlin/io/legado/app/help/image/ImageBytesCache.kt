package io.legado.app.help.image

import io.legado.app.help.FileUtilsCommon
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
 * - 内存: 32 条 LRU (对齐 [CoverDecodeFetcher] 的 decodedBytesCache)
 * - 磁盘: `{cachePath}/image_cache/{md5(url)}_{isCover}`, 存**解密后**字节
 *   (对齐原版 Glide DATA 缓存的是 fetcher 输出流 = 解密后字节; key 区分封面/正文规则,
 *   避免同一 url 两种规则互相污染 —— 原版 Glide 缓存 key 不区分, 此处语义更严格)
 * - 失败结果 (null) 不入缓存; 磁盘不可用时静默降级为纯内存缓存
 */
internal object ImageBytesCache {

    private const val MAX_MEMORY_ENTRIES = 32

    /** 磁盘文件数上限, 超限时按文件名排序淘汰一半 (md5 文件名均匀分布, 等效随机淘汰)。 */
    private const val MAX_DISK_FILES = 1000

    private val mutex = Mutex()

    // 无 accessOrder 构造 (native 无 (Int, Float, Boolean) 重载), 手动维护 LRU:
    // get/put 时 remove+put 刷新访问序, 首元素即最久未用 (与 JVM accessOrder=true 等效)
    private val memory = LinkedHashMap<String, ByteArray>()

    private fun memoryKey(url: String, isCover: Boolean) = "$url\u0000$isCover"

    private fun diskFileName(url: String, isCover: Boolean): String =
        "${MD5Utils.md5Encode(url)}_$isCover"

    private fun diskDir(): String =
        FileUtilsCommon.getPath(FileUtilsCommon.getCachePath(), "image_cache")

    suspend fun get(url: String, isCover: Boolean): ByteArray? {
        val k = memoryKey(url, isCover)
        mutex.withLock {
            // remove+put 刷新访问序 (手写 LRU, 见 memory 注释)
            memory.remove(k)?.let { value ->
                memory[k] = value
                return value
            }
        }
        val path = FileUtilsCommon.getPath(diskDir(), diskFileName(url, isCover))
        val bytes = FileUtilsCommon.readBytes(path)?.takeIf { it.isNotEmpty() } ?: return null
        mutex.withLock {
            memory[k] = bytes
        }
        return bytes
    }

    suspend fun put(url: String, isCover: Boolean, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val k = memoryKey(url, isCover)
        mutex.withLock {
            memory.remove(k)
            memory[k] = bytes
            while (memory.size > MAX_MEMORY_ENTRIES) {
                memory.remove(memory.entries.first().key)
            }
        }
        val dir = diskDir()
        if (!FileUtilsCommon.createFolderIfNotExist(dir)) return
        if (!FileUtilsCommon.writeBytes(
                FileUtilsCommon.getPath(dir, diskFileName(url, isCover)),
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
