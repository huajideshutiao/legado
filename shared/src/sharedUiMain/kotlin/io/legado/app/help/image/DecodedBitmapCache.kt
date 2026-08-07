package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.help.config.AppConfigProviders
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * 解码后位图的进程级 LRU (I1, 图片加载深度优化)。
 *
 * 背景: Compose 无 ImageView 复用, 位图生命周期只能显式持有; [ImageBitmapLoader] 的消费点
 * (大图查看 PhotoDialog / 阅读背景 / 阅读样式预览 / 鸿蒙漫画) 每次打开都重新解码
 * (desktop 4000px 图 ImageIO 全尺寸解码约 100-300ms CPU), 解码结果即抛。
 * 本缓存让同 URL 二次打开直接命中已解码位图, 零重复解码。
 *
 * 容量: 与 [ReaderImageCache] (阅读页内嵌图) 一致, 按 `AppConfig.bitmapCacheSize` (MB, 默认 50)
 * 预算, 超限从队首淘汰; 单张超预算时保留自身 (对齐原版 ensureLruCacheSize 扩容而非丢弃当前图)。
 *
 * key 含 (url + sourceOrigin + isCover + 目标采样尺寸): 书源维度隔离 (不同书源同 URL 互不
 * 污染, 与 [ImageBytesCache] 同维度); 采样尺寸隔离 (不同消费尺寸各解一份, 互不覆盖)。
 *
 * 与既有缓存的共存:
 * - [ReaderImageCache] (阅读页内嵌图, 按书清空): 相互独立, 阅读器刷新图片只清它不清本缓存
 * - [ReaderBackgroundImageCache] (阅读背景 4 条): 背景图也走 [ImageBitmapLoader], 本缓存是
 *   其外层二级; 背景切换靠本缓存预算淘汰, 不主动清 (避免误伤其他消费点)
 * - [ImageBytesCache] (字节层): 本缓存在其之上, 只缓存解码结果, 字节缓存不变
 * - Coil3 封面管线: 不经过本类 (走 Coil 自身内存缓存)
 *
 * 统一清缓存入口: 设置页"清缓存" ([io.legado.app.ui.route.OtherConfigRoute]) 与各端
 * `ReadBookPlatform.clearImageCache` (退出阅读) 均挂 [clear]。
 *
 * 验证码等同 URL 每次返回新图的场景, 调用方传 `useBitmapCache=false` 不进本缓存。
 */
object DecodedBitmapCache {

    private val lock = SynchronizedObject()

    /** 手写 LRU: 命中时先 remove 再 put 把条目挪到队尾, 超预算从队首淘汰。 */
    private val bitmaps = LinkedHashMap<String, ImageBitmap>()
    private var cachedBytes = 0L

    private val maxBytes: Long
        get() = runCatching { AppConfigProviders.get().bitmapCacheSize }
            .getOrDefault(50).coerceIn(1, 1024) * 1024L * 1024L

    /** 缓存 key: 书源维度 (origin 为空时与旧版格式一致) + 封面/正文规则 + 目标采样尺寸。 */
    fun cacheKey(
        url: String,
        origin: String?,
        isCover: Boolean,
        widthPx: Int = 0,
        heightPx: Int = 0,
    ): String =
        if (origin.isNullOrEmpty()) "$url\u0000$isCover\u0000$widthPx\u0000$heightPx"
        else "$url\u0000$origin\u0000$isCover\u0000$widthPx\u0000$heightPx"

    /** 已解码位图; 未加载 / 已淘汰返回 null (命中时刷新 LRU 访问序)。 */
    fun get(key: String): ImageBitmap? = synchronized(lock) {
        bitmaps.remove(key)?.also { bitmaps[key] = it }
    }

    /** 写入解码结果; 超预算淘汰最久未用条目。 */
    fun put(key: String, bitmap: ImageBitmap) {
        synchronized(lock) {
            bitmaps.remove(key)?.let { cachedBytes -= it.byteSize() }
            bitmaps[key] = bitmap
            cachedBytes += bitmap.byteSize()
            val limit = maxBytes
            val iterator = bitmaps.entries.iterator()
            // 单张图超预算时保留自身 (对齐 ReaderImageCache/原版 ensureLruCacheSize 语义)
            while (cachedBytes > limit && bitmaps.size > 1 && iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == key) continue
                cachedBytes -= entry.value.byteSize()
                iterator.remove()
            }
        }
    }

    /** 清空全部解码位图 (设置页清缓存 / 退出阅读挂此入口)。 */
    fun clear() {
        synchronized(lock) {
            bitmaps.clear()
            cachedBytes = 0
        }
    }

    private fun ImageBitmap.byteSize(): Long = width.toLong() * height.toLong() * 4L
}
