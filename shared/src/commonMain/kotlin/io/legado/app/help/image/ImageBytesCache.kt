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
 *   SourceHeaderNetworkClient 解密下沉替代)
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

    /** 磁盘文件数上限, 超限时按 mtime 淘汰到 [DISK_PRUNE_KEEP_FILES] 水位。 */
    private const val MAX_DISK_FILES = 1000

    /**
     * 淘汰水位 (上限的 80%): 一次淘汰到水位线, 后续写盘不再反复触发清理。
     * 对照原版 Glide `LruDiskCache` 的 evictAllBytes 语义 (超 maxSize 后淘汰到阈值以下,
     * 而不是"一删一半"反复抖动)。
     */
    private const val DISK_PRUNE_KEEP_FILES = MAX_DISK_FILES * 4 / 5

    /** 每这么多次写盘无条件重新列目录校准一次计数, 防其他写者 (Coil / 手清目录) 让计数失真。 */
    private const val DISK_COUNT_CALIBRATE_EVERY = 256

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

    /** 各目录文件数估计值 (在 [mutex] 内读写); 无条目 = 未校准。 */
    private val diskFileCounts = HashMap<String, Int>()
    private var putCount = 0

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

    /**
     * 磁盘文件数超限时按 mtime 升序淘汰到 [DISK_PRUNE_KEEP_FILES] 水位。
     *
     * 与旧实现的两处修正: ① 旧版每次写盘都 `listFiles` + 排序, 目录堆到上限后等于每存一张图
     * 都全目录扫一遍并**删掉一半** (md5 文件名的字典序是随机序, 一半里必然包含刚用的条目),
     * 造成"删了又重下"的缓存抖动; ② 现改为先用计数判定是否可能超限, 未达上限零 IO,
     * 超限时一次淘汰到水位, 并按最久未用先走。
     *
     * 整段在 [mutex] 内: 计数与目录实际内容必须一致, 否则并发写盘会重复列目录/重复淘汰。
     * 代价可控 —— 列目录只在"已达上限或每 [DISK_COUNT_CALIBRATE_EVERY] 次写盘"时发生。
     */
    private suspend fun pruneIfDiskOverflow(dir: String) {
        mutex.withLock {
            putCount++
            val known = diskFileCounts[dir]
            val dueCalibrate = putCount % DISK_COUNT_CALIBRATE_EVERY == 0
            if (known != null && known < MAX_DISK_FILES && !dueCalibrate) return@withLock
            val files = FileUtilsCommon.listFiles(dir)
            if (files.size <= MAX_DISK_FILES) {
                diskFileCounts[dir] = files.size
                return@withLock
            }
            val victims = files
                .sortedBy { FileUtilsCommon.lastModified(it) }
                .take(files.size - DISK_PRUNE_KEEP_FILES)
            victims.forEach { FileUtilsCommon.delete(it) }
            diskFileCounts[dir] = files.size - victims.size
        }
    }

    /**
     * 清空本类落在**封面持久区**的字节 (设置页"清除封面缓存"与本类的持久写入成对)。
     *
     * 不顺手清它们的话, 持久区会被两处写 (Coil MultiDiskCache + 本类 image_cache_p) 各占一半,
     * 用户清了 Coil 区仍发现封面没消失。
     *
     * 同时整表清 [memory]: 内存层的 key 不带持久/临时维度 ([cacheKey]), 单独圈出"哪些是从
     * 持久区来的"做不到; 不一起清的话磁盘删完了但封面全从内存命中, 用户观感就是"没清掉"。
     */
    suspend fun clearPersistent() {
        val dir = runCatching { DataStorageProviders.getOrNull()?.bookCoverCacheDir }.getOrNull()
            ?: return
        val target = FileUtilsCommon.getPath(dir, "image_cache_p")
        FileUtilsCommon.listFiles(target).forEach { FileUtilsCommon.delete(it) }
        mutex.withLock { memory.clear() }
    }
}
