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
        if (key.endsWith(COVER_KEY_SUFFIX)) return covers.openSnapshot(key)
        return temporary.openSnapshot(key) ?: covers.openSnapshot(key + COVER_KEY_SUFFIX)
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

    override fun shutdown() {
        covers.shutdown()
        temporary.shutdown()
    }
}

/**
 * 装配双区磁盘缓存。持久区目录取 [io.legado.app.help.storage.DataStorage.bookCoverCacheDir]
 * (Android `filesDir/covers`, 与原版 Glide 同址), 取不到时整体回退单区。
 *
 * @param temporaryDir 临时区目录 (各端自己的缓存目录下的 image_cache)
 */
fun buildImageDiskCache(temporaryDir: String): DiskCache {
    val temporary = DiskCache.Builder()
        .directory(temporaryDir.toPath())
        .maxSizeBytes(TEMP_CACHE_MAX_BYTES)
        .build()
    val coversDir = runCatching { DataStorageProviders.getOrNull()?.bookCoverCacheDir }.getOrNull()
        ?: return temporary
    val covers = DiskCache.Builder()
        .directory(coversDir.toPath())
        .maxSizeBytes(COVER_CACHE_MAX_BYTES)
        .build()
    return MultiDiskCache(covers, temporary)
}
