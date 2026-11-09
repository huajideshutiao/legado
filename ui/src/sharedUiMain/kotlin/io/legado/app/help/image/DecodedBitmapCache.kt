package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.constant.PreferKey
import io.legado.app.help.CommonLruCache
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
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
 * 容量: 解码主表用现成 [CommonLruCache] 按字节裁剪（固定上限 [decodedBitmapCacheMaxBytes]，与正文
 * 图片设置无关）；超限从最久未用一端淘汰，单张即超限的图不留在表里。
 * 封面复用条目存在各端现有有界缓存里（三端 Coil 内存缓存 / 本端主表），不用独立额度。
 * 预算统计的是本进程内这几张表的强引用，不是全进程 PSS 或位图上限。
 *
 * key 含 (url + sourceOrigin + isCover + 目标采样尺寸): 书源维度隔离 (不同书源同 URL 互不
 * 污染, 与 [ImageBytesCache] 同维度); 采样尺寸隔离 (不同消费尺寸各解一份, 互不覆盖)。
 *
 * 与既有缓存的共存:
 * - [ReaderImageCache] (阅读页内嵌图): 位图存放与字节预算在 [BodyImageStore], 与本表相互独立
 * - [ReaderBackgroundImageCache] (阅读背景 4 条): 背景图也走 [ImageBitmapLoader], 本缓存是
 *   其外层二级; 背景切换靠本缓存预算淘汰, 不主动清 (避免误伤其他消费点)
 * - [ImageBytesCache] (字节层): 本缓存在其之上, 只缓存解码结果, 字节缓存不变
 * - 封面管线: Android/Desktop/iOS 三端真封面走 Coil 自身内存/磁盘缓存, **不进解码主表**
 *   (那些条目只给大图 / 阅读背景 / 漫画的解码复用用); OHOS 无 Coil3, 真封面经
 *   [ImageBitmapLoader] 解码后落本主表, 由封面用途登记区分。例外两类由 [io.legado.app.ui.bookshelf.SharedCoverContent]
 *   在本类上手工挂账: ① **默认封面占位**位图挂进主表 (避免每条封面为占位再走一遍
 *   图片管线, key 额外拼上封面重载信号) —— 书籍与分组封面都挂; ② **真封面解码结果**挂进单列
 *   已登记的封面用途 (见 [peekCover] / [recordCover]), 让同一张封面在书架↔详情↔列表间
 *   同步取回, 首帧不再摆默认封面 —— 只有书籍封面挂 (分组封面无跨页复用方); 大图链路
 *   ([findByUrl]) 不读封面用途条目, 因为里面可能是按列表尺寸解的缩略图
 *
 * 清缓存入口按语义分两档: 设置页"清缓存" ([io.legado.app.ui.route.OtherConfigRoute]) 清全部
 * ([clear]); 退出阅读的 `clearImageCache` 在 iOS/鸿蒙/桌面三端只清解码主表 ([clearDecoded])
 * —— 封面用途条目要跨页面存活, 退出阅读把它清掉就等于书架首帧真封面失效
 * (Android 端退出阅读走 `ImageProvider.clear()`, 同时清掉与 web 出图共用的正文位图表)。
 *
 * 验证码等同 URL 每次返回新图的场景, 调用方传 `useBitmapCache=false` 不进本缓存。
 */
object DecodedBitmapCache {

    private val lock = SynchronizedObject()

    /**
     * 解码主表 (按字节裁切的现成 LRU), 实现见 [DecodedCoverTable]。
     *
     * 惰性建立在首个真实使用点: 纯 [cacheKey] 构造不读配置, 对象初始化也不因本表提前要求配置就绪。
     */
    private var table: DecodedCoverTable? = null

    /** 调用方持锁: 惰性建立并在同锁内发布, 并行请求不会各建一份 LRU 互相覆盖。 */
    private fun tableLocked(): DecodedCoverTable =
        table ?: DecodedCoverTable(
            lock = lock,
            cache = CommonLruCache(decodedBitmapCacheMaxBytes.toInt()) { _, entry -> entry.bitmap.byteSizeInt() },
        ).also { table = it }

    /** 正常入口: 读指针与惰性建立都在 owner 锁内。 */
    private fun tableForUse(): DecodedCoverTable = synchronized(lock) { tableLocked() }

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
    fun get(key: String): ImageBitmap? = synchronized(lock) { table?.get(key) }

    /**
     * 按 URL 前缀查找已解码的主表位图 (忽略尺寸/书源等参数维度),
     * 供大图查看器在打开首帧即刻同步获取现成图, 0 延迟即可起飞。
     *
     * 只认解码类条目: 封面用途登记的条目是按列表尺寸解的缩略图, 不得冒充高清图。
     */
    fun findByUrl(url: String): ImageBitmap? = synchronized(lock) { table?.findDecodedByUrl(url) }

    /** 写入解码结果; 超预算淘汰最久未用条目。 */
    fun put(key: String, bitmap: ImageBitmap) {
        tableForUse().put(key, bitmap)
    }

    /** 只取已建立的解码主表 (冷查询不初始化预算/配置); 未建立返回 null。 */
    internal fun existingDecodeTable(): CoverReuseStore? = synchronized(lock) { table }

    /** 本解码表自身作为封面复用存储时的登记入口 (仅 OHOS 端使用)。 */
    internal fun decodeTableForWrite(): CoverReuseStore = tableForUse()

    /** 按固定上限设置主表容量, 与主表建立/写入同一把 owner 锁。 */
    fun applyBudget() {
        synchronized(lock) {
            // 建立/调整与发布在同一锁内, 并发首次使用不会各建一份 LRU
            tableLocked().resize(decodedBitmapCacheMaxBytes.toInt())
        }
    }

    /**
     * URL 级封面查询: 调用方不知道来源与分区。
     *
     * 只读本端已登记的封面用途条目并按 URL 取当前可复用最大面积; 空地址不参与封面复用。
     * 缓存尚未建立时无可读项, 不为这次查询去初始化预算/配置。
     */
    fun peekCover(url: String): ImageBitmap? {
        if (url.isEmpty()) return null
        val store = currentCoverReuseStore() ?: return null
        return CoverReuseEntry.peekByUrl(store, url)
    }

    /**
     * 身份级封面查询: [origin] 由调用方明确给出 (null = 明确没有来源);
     * [persistent] 为 null 表示分区未知, 按跨分区只读。
     */
    fun peekCover(url: String, origin: String?, persistent: Boolean?): ImageBitmap? {
        if (url.isEmpty()) return null
        val store = currentCoverReuseStore() ?: return null
        return CoverReuseEntry.peekQualified(store, url, origin, persistent)
    }

    /**
     * URL 级封面登记: 来源与分区都未给出, 按未知范围登记。
     *
     * 只有真实的封面消费点调用它; 背景/头像/默认封面等通用解码走 [put], 不标封面用途。
     */
    fun recordCover(url: String, bitmap: ImageBitmap) {
        if (url.isEmpty()) return
        val store = io.legado.app.help.image.coverReuseStoreForWrite()
        CoverReuseEntry.recordByUrl(store, url, bitmap)
    }

    /** 身份级实际封面登记: 逻辑地址 + 真实来源 (null = 无来源) + 真实分区。 */
    fun recordCover(url: String, origin: String?, persistent: Boolean, bitmap: ImageBitmap) {
        if (url.isEmpty()) return
        val store = io.legado.app.help.image.coverReuseStoreForWrite()
        CoverReuseEntry.recordQualified(store, url, origin, persistent, bitmap)
    }

    /**
     * 只清解码类条目 (退出阅读挂此入口): 封面用途登记的条目跨页面存活,
     * 清了就等于书架首帧真封面失效。
     */
    fun clearDecoded() {
        synchronized(lock) { table?.clearDecoded() }
    }

    /**
     * 清空全部解码位图与已登记封面用途条目 (设置页清缓存 / 退出阅读挂此入口)。
     *
     * 封面用途条目在各端现有有界缓存里, 按用途键精确清除; 普通图片条目与磁盘内容不动。
     */
    fun clear() {
        synchronized(lock) { table?.clear() }
        currentCoverReuseStore()?.clearCoverEntries()
    }
}

/**
 * 图片缓存预算的来源与分摊。
 *
 * 正文内嵌图表预算 ([bodyImageCacheMaxBytes], 由 [BodyImageStore] 持有) 取设置项
 * `bitmapCacheSize` (MB, 默认 50) 全额, 用户改这一项只影响正文表。
 *
 * 非正文解码位图主表预算是固定独立上限 ([decodedBitmapCacheMaxBytes], 200MiB), 不随正文设置变化;
 * 该表也承载 OHOS 端真封面复用条目, 两者共用这一个上限。
 * 三端真封面复用进各端 Coil 自身的内存缓存, 沿用其默认预算, 不受正文设置影响。
 *
 * 各表上限相加也不是全进程图片占用, 更不是 PSS / 位图上限。
 */
internal val bitmapCacheMaxBytes: Long
    get() = AppConfigProviders.get().bitmapCacheSize.coerceIn(1, 1024) * 1024L * 1024L

/** 正文图片表预算: 设置值全额 ([BodyImageStore] 的字节上限)。 */
internal val bodyImageCacheMaxBytes: Long
    get() = bitmapCacheMaxBytes

/** 进程级解码位图主表预算: 固定 200MiB, 与正文设置无关。 */
internal val decodedBitmapCacheMaxBytes: Long
    get() = 200L * 1024 * 1024

private val budgetLock = SynchronizedObject()
private var budgetObserved = false

/**
 * 安装正文预算订阅: 正文设置项改动后正文图片表立即按新上限裁剪, 不等下一张图插入。
 *
 * 宿主启动期装一次 (见 [registerReaderImageResolver]), 之后不再增删; 装完立即对齐一次当前设置,
 * 以影响进程启动后先写入的条目。重复调用不重复注册。
 */
internal fun observeBitmapCacheBudget() {
    synchronized(budgetLock) {
        if (budgetObserved) return
        budgetObserved = true
    }
    PreferenceProviders.get().addPreferenceChangeListener { key ->
        if (key.isEmpty() || key == PreferKey.bitmapCacheSize) {
            bodyImageStore().applyBudget()
        }
    }
    bodyImageStore().applyBudget()
}

/** 位图占用字节 (ARGB_8888 口径, 对照原版 ensureLruCacheSize 的 byteCount)。 */
internal fun ImageBitmap.byteSize(): Long = width.toLong() * height.toLong() * 4L

/** 位图像素面积 (封面登记的面积规则口径, 与字节口径无关)。 */
internal fun ImageBitmap.pixelArea(): Long = width.toLong() * height
