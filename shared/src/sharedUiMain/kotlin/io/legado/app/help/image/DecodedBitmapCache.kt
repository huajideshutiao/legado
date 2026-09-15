package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.image.DecodedBitmapCache.clear
import io.legado.app.help.image.DecodedBitmapCache.put
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
 * - Coil3 封面管线: 四端真封面均走 Coil 自身内存/磁盘缓存, **不进主 [bitmaps]** (那些条目只给
 *   大图 / 阅读背景 / 漫画的解码复用用)。例外两类由 [io.legado.app.ui.bookshelf.SharedBookCover] /
 *   SharedGroupCover 在本类上手工挂账: ① **默认封面占位**位图挂进主 [bitmaps] (避免每条封面
 *   为占位再走一遍图片管线, key 额外拼上封面重载信号); ② **真封面解码结果**挂进单列的封面小表
 *   ([peekCover] / [recordCover], 预算为主缓存 1/8), 让同一张封面在书架↔详情↔列表间同步取回,
 *   首帧不再摆默认封面——大图链路 ([findByUrl]) 不读这张小表, 因为里面是按列表尺寸解的糊图
 *
 * 清缓存入口按语义分三档: 设置页"清缓存" ([io.legado.app.ui.route.OtherConfigRoute]) 清全部
 * ([clear]); 设置页"清封面缓存"清封面小表 ([clearCovers]); 退出阅读的 `clearImageCache` 在
 * iOS/鸿蒙/桌面三端只清解码主表 ([clearDecoded]) —— 封面小表要跨页面存活, 退出阅读把它清掉就等于
 * 书架首帧真封面失效 (Android 端退出阅读走 `ImageProvider.clear()` 自有链路, 不经过本缓存)。
 *
 * 验证码等同 URL 每次返回新图的场景, 调用方传 `useBitmapCache=false` 不进本缓存。
 */
object DecodedBitmapCache {

    private val lock = SynchronizedObject()

    /** 手写 LRU: 命中时先 remove 再 put 把条目挪到队尾, 超预算从队首淘汰。 */
    private val bitmaps = LinkedHashMap<String, ImageBitmap>()
    private var cachedBytes = 0L

    /**
     * 封面显示专用小表: url → 最近一次已解码过的封面位图 (忽略尺寸/书源/标志维度)。
     *
     * 为何不放在主 [bitmaps] 里: 主缓存的预算是给大图查看 / 阅读背景 / 漫画的**解码复用**
     * 用的, 而列表每滚过一本书都会往里塞一张被降采样过的封面 (单张数百 KB), 几十本就
     * 会把这些条目冲干净; 反之真封面也进不了 [findByUrl] 的线性扫描 (网格每项组合各扫一遍)。
     * 本表只做一件事: 让同一 url 的封面在书架 ↔ 详情 ↔ 列表之间同步取回, 首帧不摆默认封面。
     *
     * 预算取主缓存的 1/8 (默认 50MB → 6.25MB) 并独立淘汰, 不动主缓存条目; 同 url 写入是
     * **覆盖**旧值 (封面可能被重烘焙/重下载, 新字节必须生效)。不参与大图链路: 本表里的位图
     * 是按列表尺寸解的, 铺全屏会糊, 故 [findByUrl] 不读它。
     */
    private val coversByUrl = LinkedHashMap<String, ImageBitmap>()
    private var coverBytes = 0L

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

    /**
     * 按 URL 前缀查找已缓存的位图 (忽略尺寸/书源等参数维度),
     * 供大图查看器在打开首帧即刻同步获取现成封面位图, 0 延迟即刻起飞。
     */
    fun findByUrl(url: String): ImageBitmap? = synchronized(lock) {
        if (url.isEmpty()) return null
        val prefix = "$url\u0000"
        for ((k, v) in bitmaps) {
            if (k.startsWith(prefix) || k == url) return v
        }
        null
    }

    /** 写入解码结果; 超预算淘汰最久未用条目。 */
    fun put(key: String, bitmap: ImageBitmap) {
        synchronized(lock) {
            bitmaps.remove(key)?.let { cachedBytes -= it.byteSize() }
            bitmaps[key] = bitmap
            cachedBytes += bitmap.byteSize()
            val limit = bitmapCacheMaxBytes
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

    /** 取本表已解码封面位图; 未命中返回 null。命中不刷访问序: 本表只按面积留一张, 命中顺序无意义。 */
    fun peekCover(url: String): ImageBitmap? = synchronized(lock) {
        if (url.isEmpty()) return null
        coversByUrl[url]
    }

    /**
     * 记录已解码的封面位图。同 url **只留面积最大的一张**：
     * 书架格子（小档）解完不该把详情页已解好的大档挤掉, 否则首帧同步取回拿到的是小图放大的糊图；
     * 面积相同（封面被重烘焙/重下载）时新值照常生效。
     */
    fun recordCover(url: String, bitmap: ImageBitmap) {
        if (url.isEmpty()) return
        synchronized(lock) {
            val old = coversByUrl.remove(url)
            if (old != null) coverBytes -= old.byteSize()
            val keep = if (old != null && old.width.toLong() * old.height > bitmap.width.toLong() * bitmap.height) old else bitmap
            coversByUrl[url] = keep
            coverBytes += keep.byteSize()
            val limit = bitmapCacheMaxBytes / 8
            val iterator = coversByUrl.entries.iterator()
            while (coverBytes > limit && coversByUrl.size > 1 && iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == url) continue
                coverBytes -= entry.value.byteSize()
                iterator.remove()
            }
        }
    }

    /** 只清解码位图主表（退出阅读挂此入口）: 封面小表跨页面存活, 清了就等于首帧真封面失效。 */
    fun clearDecoded() {
        synchronized(lock) {
            bitmaps.clear()
            cachedBytes = 0
        }
    }

    /** 只清封面小表（设置页"清除封面缓存"挂此入口, 与封面持久区同语义）。 */
    fun clearCovers() {
        synchronized(lock) {
            coversByUrl.clear()
            coverBytes = 0L
        }
    }

    /** 清空全部解码位图 (设置页清缓存 / 退出阅读挂此入口)。 */
    fun clear() {
        synchronized(lock) {
            bitmaps.clear()
            cachedBytes = 0
            coversByUrl.clear()
            coverBytes = 0L
        }
    }
}

/** 解码位图 LRU 预算 (`AppConfig.bitmapCacheSize` MB → 字节), 本缓存与 [ReaderImageCache] 共用。 */
internal val bitmapCacheMaxBytes: Long
    get() = AppConfigProviders.get().bitmapCacheSize.coerceIn(1, 1024) * 1024L * 1024L

/** 位图占用字节 (ARGB_8888 口径, 对照原版 ensureLruCacheSize 的 byteCount)。 */
internal fun ImageBitmap.byteSize(): Long = width.toLong() * height.toLong() * 4L
