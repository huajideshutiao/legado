package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.help.CommonLruCache
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * 解码位图表 (OHOS 真封面经解码管线落这张表, 其余三端大图/背景/漫画的解码复用也走它)。
 *
 * 表项分两类用途: 解码类 (给大图查看/背景/漫画复用) 与封面用途 (已登记的真实封面关联)。
 * 两者共用一个 [CommonLruCache] 与其字节上限, 不新开表/额度/外部索引。
 *
 * 表键分两种: 原公开解码键 ([TableKey.Decode], 行为与粒度不变) 与规范封面键
 * ([TableKey.Cover])。登记一律由 [CoverReuse.record] 走四个真实操作, 不另存第二份:
 * 该图已有记录就在原记录上并入关联, 因此原解码键之后仍然命中。
 *
 * 正常可实例化: 生产入口 [DecodedBitmapCache] 与测试用同一实现, 各自持有自己那份真实 LRU。
 */
internal class DecodedCoverTable(
    private val lock: SynchronizedObject,
    private val cache: CommonLruCache<TableKey, Entry>,
) : CoverReuseStore {

    /** 表项: 原解码位图 + 真实底层身份 + 已登记的封面关联 (解码类为 null)。 */
    internal class Entry(
        val bitmap: ImageBitmap,
        val native: Any,
        val usages: Set<CoverUsage>? = null,
    ) {
        /** 是否已作为封面用途登记过。 */
        val isCover: Boolean get() = usages != null
    }

    /** 表键: 原解码键保持字符串行为, 封面条目按规范资源键定条目。 */
    internal sealed interface TableKey {
        data class Decode(val raw: String) : TableKey
        /** 某真实域的规范封面槽。 */
        data class Cover(val url: String, val origin: CoverOrigin, val partition: CoverPartition) : TableKey
        /** 规范槽已被别的底层图占用时, 本图自己的受限槽 (按真实资源身份, 不绑某个 URL 域)。 */
        data class ByNative(val native: Any) : TableKey
    }

    // region 解码类 (原公开行为)

    fun get(rawKey: String): ImageBitmap? = synchronized(lock) {
        cache[TableKey.Decode(rawKey)]?.bitmap
    }

    /**
     * 写入解码结果; 单张超上限时不留同键旧值。
     *
     * 同一底层图再次写入 (仅换包装) 保留它已登记的封面关联;
     * 换了底层图的按真实新资源处理, 不继承未经确认的旧归属。
     */
    fun put(rawKey: String, bitmap: ImageBitmap) {
        synchronized(lock) {
            val key = TableKey.Decode(rawKey)
            if (bitmap.byteSizeInt() > cache.maxSize()) {
                cache.remove(key)
                return
            }
            val native = bitmap.resourceIdentity()
            val existing = cache[key]
            val usages = existing?.usages?.takeIf { existing.native == native }
            cache.put(key, Entry(bitmap, native, usages))
        }
    }

    /** 按 URL 前缀找解码类位图 (封面登记的缩略图不得冒充高清)。 */
    fun findDecodedByUrl(url: String): ImageBitmap? = synchronized(lock) {
        if (url.isEmpty()) return null
        val prefix = "$url\u0000"
        for ((key, entry) in cache.snapshot()) {
            if (entry.isCover) continue
            val raw = (key as? TableKey.Decode)?.raw ?: continue
            if (raw.startsWith(prefix) || raw == url) return entry.bitmap
        }
        null
    }

    /** 只清解码类条目 (退出阅读): 封面用途条目跨页面存活。 */
    fun clearDecoded() {
        synchronized(lock) {
            for ((key, entry) in cache.snapshot()) {
                if (!entry.isCover) cache.remove(key)
            }
        }
    }

    /** 清空整张表 (设置页清缓存)。 */
    fun clear() {
        synchronized(lock) {
            for (key in cache.snapshot().keys) cache.remove(key)
        }
    }

    /** 按当前设置立即裁剪。 */
    fun resize(maxSize: Int) {
        synchronized(lock) { cache.resize(maxSize) }
    }

    // endregion

    // region CoverReuseStore (真实槽身份)

    /** 与 get/put/clear/resize 同一把 owner 锁: 登记决策与提交不会被并发解码/清空插入。 */
    override fun <T> withOwnerLock(block: () -> T): T = synchronized(lock) { block() }

    /** 条目槽身份由本表产生, 类型不符是程序不变量破坏, 不能静默当登记完成。 */
    private fun slotOf(entry: CoverEntry): TableKey =
        entry.slot as? TableKey ?: error("封面条目的槽身份不属于本表: ${entry.slot}")

    /** 主表认同 Native 的那一条现有记录 (非变更快照, 不为它另开槽/索引)。 */
    override fun slotOfNative(url: String, scope: CoverScope, native: Any): CoverEntry? = synchronized(lock) {
        cache.snapshot().entries
            .firstOrNull { (_, entry) -> entry.native == native }
            ?.let { (key, entry) -> CoverEntry(key, entry.bitmap, entry.usages.orEmpty()) }
    }

    override fun slotsOf(url: String, scope: CoverScope): List<CoverEntry> = synchronized(lock) {
        val usage = CoverUsage(url, scope)
        cache.snapshot().mapNotNull { (key, entry) ->
            val usages = entry.usages ?: return@mapNotNull null
            if (usage !in usages) return@mapNotNull null
            CoverEntry(key, entry.bitmap, usages)
        }
    }

    /** 只从主表快照里按完整关联筛出候选, 取快照本身不改变访问序。 */
    override fun candidates(query: CoverQuery): List<CoverEntry> = synchronized(lock) {
        cache.snapshot().mapNotNull { (key, entry) ->
            val usages = entry.usages ?: return@mapNotNull null
            val candidate = CoverEntry(key, entry.bitmap, usages)
            if (!candidate.matches(query)) return@mapNotNull null
            candidate
        }
    }

    override fun isCanonicalSlot(entry: CoverEntry, url: String, scope: CoverScope): Boolean =
        slotOf(entry) == TableKey.Cover(url, scope.origin, scope.partition)

    /**
     * 落本域的新条目。
     *
     * 规范槽已被另一个底层图占用 (它还在服务别的真实域) 时, 本图落在自己按资源身份
     * 命名的槽上; 不覆盖那条记录、不复制它的像素, 两者都受同一个 LRU 预算。
     */
    override fun putNew(url: String, scope: CoverScope, bitmap: ImageBitmap, usages: Set<CoverUsage>): Boolean =
        synchronized(lock) {
            if (bitmap.byteSizeInt() > cache.maxSize()) return false
            val native = bitmap.resourceIdentity()
            val canonical = TableKey.Cover(url, scope.origin, scope.partition)
            val occupied = cache[canonical]?.let { it.native != native } == true
            val key = if (occupied) TableKey.ByNative(native) else canonical
            cache.put(key, Entry(bitmap, native, usages))
            true
        }

    /**
     * 把该规范封面槽里记录的显示复用换成 [bitmap]。
     *
     * 只对规范封面槽调用: 解码约束槽的像素是它自己的 URL/来源/采样产物, 不在这里替换。
     */
    override fun replace(entry: CoverEntry, bitmap: ImageBitmap, usages: Set<CoverUsage>): Boolean =
        synchronized(lock) {
            val key = slotOf(entry)
            require(key is TableKey.Cover) { "只有规范封面槽可以被就地换图: $key" }
            if (bitmap.byteSizeInt() > cache.maxSize()) return false
            cache.put(key, Entry(bitmap, bitmap.resourceIdentity(), usages))
            true
        }

    override fun updateUsages(entry: CoverEntry, usages: Set<CoverUsage>) {
        synchronized(lock) {
            val key = slotOf(entry)
            val current = cache[key] ?: return
            cache.put(key, Entry(current.bitmap, current.native, usages))
        }
    }

    /** 只清已登记封面用途的条目 (含曾登记后归属为空的), 解码类条目不动。 */
    override fun clearCoverEntries() {
        synchronized(lock) {
            for ((key, entry) in cache.snapshot()) {
                if (entry.usages != null) cache.remove(key)
            }
        }
    }

    /** 非封面条目即解码类条目: 与 [clearDecoded] 同一语义, 复用它的遍历。 */
    override fun clearNonCoverEntries() = clearDecoded()

    override fun markUsed(entry: CoverEntry) {
        synchronized(lock) {
            val key = slotOf(entry)
            cache[key]
        }
    }

    // endregion
}
