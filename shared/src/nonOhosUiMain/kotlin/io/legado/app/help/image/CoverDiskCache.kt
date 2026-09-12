package io.legado.app.help.image

import coil3.disk.DiskCache
import io.legado.app.help.storage.DataStorageProviders
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * 书架封面 diskCacheKey 后缀: [MultiDiskCache] 按此分流到持久区。
 * 对照原版 Glide `ImageLoader.load(.., inBookshelf)` 的 `.signature(ObjectKey("covers"))`
 * 与 `MultiDiskCacheFactory` 的 `key.contains("covers")` 路由。
 */
private const val COVER_KEY_SUFFIX = "#covers"

/** 封面持久区容量, 对齐原版 `MultiDiskCacheFactory` 的 256*1024*1000。 */
private const val COVER_CACHE_MAX_BYTES = 256L * 1024 * 1000

/** 临时区容量, 对齐原版 Glide `builder.setDiskCache(MultiDiskCacheFactory(context, 512*1024*1000))`。 */
private const val TEMP_CACHE_MAX_BYTES = 512L * 1024 * 1000

/** 把封面 url 转成落持久区的 diskCacheKey。 */
fun coverDiskCacheKey(url: String): String = url + COVER_KEY_SUFFIX

/**
 * 双区磁盘缓存: 书架封面落应用数据目录 (系统清缓存清不掉), 其余图片落缓存目录。
 *
 * 移植原版 `MultiDiskCacheFactory` —— 书源失效后封面不可重获, 不能跟普通图片同区被系统抹掉。
 * 三端共享 (android/jvm/ios): iOS 持久区经 commonMain `bookCoverCacheDir` 默认实现
 * 解析到沙盒 Documents/covers, 与 Android filesDir/covers 同语义。
 */
class MultiDiskCache(
    private val covers: DiskCache,
    private val temporary: DiskCache,
) : DiskCache {

    override val size: Long get() = covers.size + temporary.size

    override val maxSize: Long get() = covers.maxSize + temporary.maxSize

    override val directory: Path get() = temporary.directory

    override val fileSystem: FileSystem get() = temporary.fileSystem

    /** 读: 命中不了本区时查另一区 —— 导出 epub 封面等调用点拿裸 url 查, 不带后缀也要能找到。 */
    override fun openSnapshot(key: String): DiskCache.Snapshot? {
        if (key.endsWith(COVER_KEY_SUFFIX)) {
            // 书架/封面读: 持久区未命中但临时区已有同图裸 url 条目 (该书之前以"未加架"身份
            // 在发现/搜索页下过) —— 对照原版缺陷 #5「书架内外两份缓存 + 加架后重下」的修复:
            // 把临时区条目提升到持久区后交给调用方, 不再重复走网络 (一次文件拷贝 << 一次下载+解密)。
            return covers.openSnapshot(key) ?: promoteFromTemporary(key)
        }
        return temporary.openSnapshot(key) ?: covers.openSnapshot(key + COVER_KEY_SUFFIX)
    }

    /**
     * 临时区条目 → 持久区。失败 (并发编辑/写盘出错) 时退回直接交临时区快照,
     * 语义仍然正确 (只是没完成提升, 下次再试), 不影响调用方读图。
     */
    private fun promoteFromTemporary(coverKey: String): DiskCache.Snapshot? {
        val plainKey = coverKey.removeSuffix(COVER_KEY_SUFFIX)
        val src = temporary.openSnapshot(plainKey) ?: return null
        val promoted = try {
            // 用 FileSystem.read 而不是手 source(): 后者返回的 Source 没人关, 每提升一张封面漏一个 FD
            val bytes = temporary.fileSystem.read(src.data) { readByteArray() }
            val editor = if (bytes.isNotEmpty()) covers.openEditor(coverKey) else null
            if (editor == null) {
                false
            } else {
                try {
                    covers.fileSystem.write(editor.data) { write(bytes) }
                    editor.commit()
                    true
                } catch (e: Exception) {
                    runCatching { editor.abort() }
                    false
                }
            }
        } catch (e: Exception) {
            false
        } finally {
            src.close()
        }
        if (!promoted) return temporary.openSnapshot(plainKey)
        // 提升成功后抹掉临时区副本: 否则同一图仍占两份 (原缺陷的另一半)
        runCatching { temporary.remove(plainKey) }
        return covers.openSnapshot(coverKey)
    }

    override fun openEditor(key: String): DiskCache.Editor? {
        return if (key.endsWith(COVER_KEY_SUFFIX)) covers.openEditor(key) else temporary.openEditor(key)
    }

    /** 删书时调用点传的是裸 url, 两区带/不带后缀都删一遍。 */
    override fun remove(key: String): Boolean {
        val plain = key.removeSuffix(COVER_KEY_SUFFIX)
        return covers.remove(plain + COVER_KEY_SUFFIX) or temporary.remove(plain)
    }

    /** 只清临时区: 用户"清除缓存"不该抹掉书架封面 (原版 `MultiDiskCacheFactory.clear` 同义)。 */
    override fun clear() = temporary.clear()

    /**
     * 单独清封面持久区。
     *
     * 修原版缺陷 #7: 原版 `MultiDiskCacheFactory` 的 `clear()` 只清 defaultsCache,
     * 而全仓无任何地方调 `Glide.clearDiskCache()`, 设置页"清除缓存"删的是 `cacheDir` +
     * `book_cache` —— `filesDir/covers` 这最多 250MB 的封面库在任何清理路径上都是死角,
     * 用户无法清掉。本方法给持久区一个显式入口 (由"清除封面缓存"设置项调用,
     * 刻意不并入"清除缓存": 书源失效后封面不可重获)。
     */
    fun clearCovers() {
        covers.clear()
        // 清了封面缓存 = 让用户重走网络, 之前被 failUrl 永久拉黑的死链也该重新试一次
        clearImageLoadFailures()
    }

    override fun shutdown() {
        covers.shutdown()
        temporary.shutdown()
    }
}

/**
 * 装配双区磁盘缓存。持久区目录取 [io.legado.app.help.storage.DataStorage.bookCoverCacheDir]
 * (Android `filesDir/covers` / iOS `Documents/covers`, 与原版 Glide 同址), 取不到时整体回退单区。
 *
 * @param temporaryDir 临时区目录 (各端自己的缓存目录下的 image_cache)
 */
fun buildImageDiskCache(temporaryDir: String): DiskCache {
    val temporary = DiskCache.Builder()
        .directory(temporaryDir.toPath())
        .maxSizeBytes(TEMP_CACHE_MAX_BYTES)
        .build()
    val coversDir = runCatching { DataStorageProviders.getOrNull()?.bookCoverCacheDir }.getOrNull()
        // 回退单区时也包 MultiDiskCache(同实例双区): 保住裸 url ←→ "url#covers" 双向兑底查询
        // (封面仍带 #covers key 写入, 裸 url 消费方如 epub 导出才能命中; 同实例双次 shutdown 安全,
        // DiskLruCache.close 幂等)
        ?: return MultiDiskCache(temporary, temporary)
    val covers = DiskCache.Builder()
        .directory(coversDir.toPath())
        .maxSizeBytes(COVER_CACHE_MAX_BYTES)
        .build()
    return MultiDiskCache(covers, temporary)
}
