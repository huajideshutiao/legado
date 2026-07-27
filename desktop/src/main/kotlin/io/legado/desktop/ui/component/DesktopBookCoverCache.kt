package io.legado.desktop.ui.component

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 桌面端封面内存 LRU 缓存 (书架 + 详情页共享, 避免重复下载解码)。
 */
private const val COVER_CACHE_MAX_SIZE = 200

/**
 * 封面内存 LRU 缓存。
 *
 * - key: coverPath (URL 或本地路径)
 * - value: ImageBitmap? (含失败 null, 避免失败时反复重试)
 * - LRU 淘汰: 超过 [COVER_CACHE_MAX_SIZE] 时移除最久未访问条目
 * - 线程安全: [synchronizedMap] 包装, 供 produceState 协程并发读写
 */
private val coverCache: MutableMap<String, ImageBitmap?> =
    java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap?>(COVER_CACHE_MAX_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>): Boolean =
                size > COVER_CACHE_MAX_SIZE
        }
    )

/**
 * 缓存是否命中 (区分"未缓存"与"缓存了 null")。
 */
fun containsCoverCache(key: String): Boolean = coverCache.containsKey(key)

/**
 * 读取缓存值 (调用方应先用 [containsCoverCache] 判断命中)。
 */
fun getCoverCache(key: String): ImageBitmap? = coverCache[key]

/**
 * 写入缓存 (含失败 null)。
 */
fun putCoverCache(key: String, value: ImageBitmap?) {
    coverCache[key] = value
}

/**
 * 命中缓存直接返回, 否则执行 [loader] 加载后写入缓存再返回。
 *
 * - 命中 (含 null): 跳过加载, 避免失败重试
 * - 未命中: 调用 [loader] (通常为 IO 加载), 结果 (含 null) 写入缓存
 */
suspend fun getOrLoadCover(key: String, loader: suspend () -> ImageBitmap?): ImageBitmap? {
    if (containsCoverCache(key)) {
        return getCoverCache(key)
    }
    val value = loader()
    putCoverCache(key, value)
    return value
}

/**
 * 清空缓存 (设置变更时刷新封面)。
 */
fun clearCoverCache() {
    coverCache.clear()
}
