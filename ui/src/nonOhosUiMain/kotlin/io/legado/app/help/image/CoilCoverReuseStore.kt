package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import coil3.Image
import coil3.memory.MemoryCache
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * 把封面复用登记到 Coil 库内内存缓存 (Android/Desktop/iOS 三端现成单例的 MemoryCache)。
 *
 * 只使用库现成接口: 条目落同一个 [MemoryCache], 受它的上限与强/弱引用策略约束,
 * 不新建缓存实例、不在表外保有图。完整关联 (地址 + 自己的来源/分区) 以原对象放在
 * [MemoryCache.Value.extras] 里; 键的 extras 只放能等值筛选的字符串字段与用途标记,
 * 查询时先按键筛候选, 只对相关条目调用取值 —— 按库的公开语义, 取值本身会刷新该条目访问序。
 *
 * @param memoryCache 现成库缓存 (生产传各端单例的 memoryCache, 测试可传同一个库的局部实例)
 * @param toCoilImage Compose 位图 → 库图片 (现成包装)
 * @param toImageBitmap 库图片 → Compose 位图 (现成包装)
 */
internal class CoilCoverReuseStore(
    private val memoryCache: MemoryCache,
    private val toCoilImage: (ImageBitmap) -> Image,
    private val toImageBitmap: (Image) -> ImageBitmap,
) : CoverReuseStore {

    /** 自家用途登记的 owner 锁 (库内部 LRU 仍由库自己加锁)。 */
    private val lock = SynchronizedObject()

    /**
     * 本适配器自己的用途条目登记边界: 决策与 [putNew]/[replace]/[updateUsages]/[markUsed]
     * 在同一把锁内。库的引擎写入与内存 LRU 有它自己的锁, 不经过这把锁。
     */
    override fun <T> withOwnerLock(block: () -> T): T = synchronized(lock) { block() }

    /** 条目槽身份由本适配器产生, 类型不符是程序不变量破坏, 不能静默当登记完成。 */
    private fun slotOf(entry: CoverEntry): MemoryCache.Key =
        entry.slot as? MemoryCache.Key ?: error("封面条目的槽身份不属于本库: ${entry.slot}")

    /**
     * 只读本次登记域的规范用途键, 不在整库封面里找同一底层图。
     *
     * 全库查找会把别的封面一并取值而按库规则预热 (相对普通引擎条目失真);
     * 同一底层图落在另一个真实域时, 由正常登记在该域建一条真实计费的用途条目。
     */
    override fun slotOfNative(url: String, scope: CoverScope, native: Any): CoverEntry? {
        val cacheKey = cacheKey(CoverKey(url, scope))
        val value = memoryCache[cacheKey] ?: return null
        val entry = value.toEntry(cacheKey)
        // 同域同底层图才算同一条记录; 换了图就交给本域的面积规则处理
        return entry.takeIf { it.bitmap.resourceIdentity() == native }
    }

    override fun slotsOf(url: String, scope: CoverScope): List<CoverEntry> {
        val usage = CoverUsage(url, scope)
        val key = CoverKey(url, scope)
        val value = memoryCache[cacheKey(key)] ?: return emptyList()
        val entry = value.toEntry(cacheKey(key))
        return if (usage in entry.usages) listOf(entry) else emptyList()
    }

    /** 只取与该查询相关的候选 (读取命中的候选会按库规则刷新访问序)。 */
    override fun candidates(query: CoverQuery): List<CoverEntry> = memoryCache.keys
        .filter { it.key == COVER_KEY && it.matches(query) }
        .mapNotNull { cacheKey ->
            val value = memoryCache[cacheKey] ?: return@mapNotNull null
            val entry = value.toEntry(cacheKey)
            if (entry.matches(query)) entry else null
        }

    /** 用途条目一律按本域规范键落地, 因此这里就是与规范键等值比较。 */
    override fun isCanonicalSlot(entry: CoverEntry, url: String, scope: CoverScope): Boolean =
        slotOf(entry) == cacheKey(CoverKey(url, scope))

    override fun putNew(
        url: String,
        scope: CoverScope,
        bitmap: ImageBitmap,
        usages: Set<CoverUsage>,
    ): Boolean {
        val image = toCoilImage(bitmap)
        // 库对不可共享的图只短暂返回一次并随即移除, 不能算可持续复用
        if (!image.shareable) return false
        val cacheKey = cacheKey(CoverKey(url, scope))
        memoryCache[cacheKey] = MemoryCache.Value(
            image = image,
            extras = mapOf(EXTRA_COVER_USAGES to usages.toSet()),
        )
        // 按库公开查询核对: 单张超上限/刚被淘汰时该 key 上并没有这张图
        val published = memoryCache[cacheKey] ?: return false
        return published.image === image
    }

    /**
     * 把规范用途条目换成新图。
     *
     * 库在"新图超过上限"时会先把同键旧值移除, 因此换图失败要把原图经同一个库入口放回,
     * 否则本域会在没有任何记录的情况下被当成已换成新图。原图自己也超过上限时库里留不住,
     * 那是库真实预算边界。
     */
    override fun replace(entry: CoverEntry, bitmap: ImageBitmap, usages: Set<CoverUsage>): Boolean {
        val cacheKey = slotOf(entry)
        val image = toCoilImage(bitmap)
        if (image.shareable) {
            memoryCache[cacheKey] = MemoryCache.Value(image, mapOf(EXTRA_COVER_USAGES to usages.toSet()))
            // 按库公开查询核对: 超上限/刚被淘汰时该 key 上没有这张图
            if (memoryCache[cacheKey]?.image === image) return true
        }
        restore(cacheKey, entry)
        return false
    }

    /** 用同一个库入口把原图放回该键 (只动这一条, 不保任何表外引用)。 */
    private fun restore(cacheKey: MemoryCache.Key, entry: CoverEntry) {
        val old = toCoilImage(entry.bitmap)
        if (!old.shareable) return
        memoryCache[cacheKey] = MemoryCache.Value(
            image = old,
            extras = mapOf(EXTRA_COVER_USAGES to entry.usages.toSet()),
        )
    }

    override fun updateUsages(entry: CoverEntry, usages: Set<CoverUsage>) {
        val cacheKey = slotOf(entry)
        val value = memoryCache[cacheKey] ?: return
        memoryCache[cacheKey] = value.copy(extras = value.extras + (EXTRA_COVER_USAGES to usages.toSet()))
    }

    /**
     * 只删自家规范用途条目: 库的 remove 会同时清掉该键的强/弱引用, 普通引擎条目与磁盘不受影响。
     *
     * 判定按自家完整用途 schema (主串 + 三个身份字段), 不只看主串同名:
     * 库里可能存在只叫 [COVER_KEY] 却没有自家字段的普通条目, 那不属于封面用途。
     * 整个清除在本适配器的 owner 锁内, 不会插进别人的登记/恢复中间。
     */
    override fun clearCoverEntries() {
        synchronized(lock) {
            memoryCache.keys
                .filter { it.isCoverUsageKey() }
                .forEach { memoryCache.remove(it) }
        }
    }

    /**
     * 只清普通图片条目: 已登记的封面用途条目保留 (退出阅读后书架/详情首帧仍可复用)。
     */
    override fun clearNonCoverEntries() {
        synchronized(lock) {
            memoryCache.keys
                .filter { !it.isCoverUsageKey() }
                .forEach { memoryCache.remove(it) }
        }
    }

    /** 自家用途条目的完整标识: 主串一致且三个身份字段齐全。 */
    private fun MemoryCache.Key.isCoverUsageKey(): Boolean =
        key == COVER_KEY &&
            extras[EXTRA_COVER_URL] != null &&
            extras[EXTRA_ORIGIN_KIND] != null &&
            extras[EXTRA_ORIGIN_VALUE] != null &&
            extras[EXTRA_COVER_PARTITION] != null

    override fun markUsed(entry: CoverEntry) {
        val cacheKey = slotOf(entry)
        memoryCache[cacheKey]
    }

    private fun cacheKey(key: CoverKey): MemoryCache.Key = MemoryCache.Key(
        COVER_KEY,
        mapOf(
            EXTRA_COVER_URL to key.url,
            EXTRA_ORIGIN_KIND to originKind(key.scope.origin),
            EXTRA_ORIGIN_VALUE to originValue(key.scope.origin),
            EXTRA_COVER_PARTITION to partitionToken(key.scope.partition),
        ),
    )

    /**
     * 候选筛选: 先按自家完整用途 schema 确认是我的条目, 再按查询条件等值判断。
     *
     * 只看键字段 (extras 等值筛选, 不对字段做反向解析), 不读取值: 普通条目不会被取值而按库规则预热,
     * 带同名 extras 的普通条目也不会被当成本适配器的元数据不变量错误。
     */
    private fun MemoryCache.Key.matches(query: CoverQuery): Boolean {
        if (!isCoverUsageKey()) return false
        val url = extras.getValue(EXTRA_COVER_URL)
        return when (query) {
            is CoverQuery.ByUrl -> url == query.url
            is CoverQuery.Qualified ->
                url == query.url &&
                    extras[EXTRA_ORIGIN_KIND] == originKind(query.origin) &&
                    (query.origin !is CoverOrigin.Known ||
                        extras[EXTRA_ORIGIN_VALUE] == originValue(query.origin)) &&
                    (query.partition == CoverPartition.Unknown ||
                        extras[EXTRA_COVER_PARTITION] == partitionToken(query.partition))
        }
    }

    private fun MemoryCache.Value.toEntry(slot: MemoryCache.Key): CoverEntry {
        // 自家写入的元数据缺失/类型不符是程序不变量, 不能默默当成空登记
        val usages = extras[EXTRA_COVER_USAGES] as? Set<*>
            ?: error("封面用途条目缺少关联元数据: $slot")
        return CoverEntry(
            slot = slot,
            bitmap = toImageBitmap(image),
            usages = usages.mapTo(mutableSetOf()) { it as CoverUsage },
        )
    }

    private fun originKind(origin: CoverOrigin): String = when (origin) {
        CoverOrigin.Unknown -> ORIGIN_KIND_UNKNOWN
        CoverOrigin.None -> ORIGIN_KIND_NONE
        is CoverOrigin.Known -> ORIGIN_KIND_KNOWN
    }

    /** 来源原值原样入键: 与类型分开存放, 不与任何标记串混在一个字段。 */
    private fun originValue(origin: CoverOrigin): String =
        (origin as? CoverOrigin.Known)?.bookUrl ?: ""

    private fun partitionToken(partition: CoverPartition): String = when (partition) {
        CoverPartition.Unknown -> PARTITION_UNKNOWN
        CoverPartition.Ephemeral -> PARTITION_EPHEMERAL
        CoverPartition.Persistent -> PARTITION_PERSISTENT
    }

    private companion object {
        /** 封面用途条目的键 (与普通引擎条目分开, 不覆盖 Coil 自己的写入路径)。 */
        const val COVER_KEY = "legado#cover"

        /** 键 extras: 只放可等值筛选的字段 (真实值原样, 不做转义/拼串)。 */
        const val EXTRA_COVER_URL = "url"
        const val EXTRA_ORIGIN_KIND = "originKind"
        const val EXTRA_ORIGIN_VALUE = "originValue"
        const val EXTRA_COVER_PARTITION = "partition"

        /** 值 extras: 完整关联集合 (进程内类型对象, 不序列化)。 */
        const val EXTRA_COVER_USAGES = "usages"

        /** 来源类型标记: 与来源原值分字段, 不与任何真实书源地址混用。 */
        const val ORIGIN_KIND_UNKNOWN = "unknown"
        const val ORIGIN_KIND_NONE = "none"
        const val ORIGIN_KIND_KNOWN = "known"

        /** 分区范围标记。 */
        const val PARTITION_UNKNOWN = "unknown"
        const val PARTITION_EPHEMERAL = "ephemeral"
        const val PARTITION_PERSISTENT = "persistent"
    }
}
