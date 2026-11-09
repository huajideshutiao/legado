package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 书源来源的登记/查询范围。
 *
 * "不知道来源"与"明确没有来源"是两件事: 前者不得命中任何已知来源的条目,
 * 后者才能与按本体（无来源）登记的封面互认。
 */
internal sealed interface CoverOrigin {
    /** 调用方没有来源信息 (不得据此猜测)。 */
    data object Unknown : CoverOrigin

    /** 资源本身没有来源 (如分组封面)。 */
    data object None : CoverOrigin

    /** 真实书源地址。 */
    data class Known(val bookUrl: String) : CoverOrigin
}

/** 字节分区的登记/查询范围。 */
internal sealed interface CoverPartition {
    /** 调用方不知道分区: 查询时表示跨分区只读。 */
    data object Unknown : CoverPartition
    data object Ephemeral : CoverPartition
    data object Persistent : CoverPartition
}

/** 一次真实登记范围: 来源 + 分区。 */
internal data class CoverScope(
    val origin: CoverOrigin,
    val partition: CoverPartition,
)

/**
 * 一条**完整**的封面登记: 逻辑地址与它自己的来源/分区绑定在一起。
 *
 * 地址与范围不能拆成两个集合: 拆开后会凭空允许"地址 A + 来源 B"这种从未成功登记过的组合。
 */
internal data class CoverUsage(
    val url: String,
    val scope: CoverScope,
)

/**
 * 规范资源键: 逻辑地址 + 自己的登记范围。
 *
 * 存储方按它定条目, 因此不同来源/分区天然落在不同条目上, 不依赖调用方随手传的键。
 */
internal data class CoverKey(
    val url: String,
    val scope: CoverScope,
)

/** 封面复用查询范围。 */
internal sealed interface CoverQuery {
    /** 原 URL 级: 只知道逻辑封面地址, 不限定来源与分区。 */
    data class ByUrl(val url: String) : CoverQuery

    /** 上下文完整: 限定真实来源; 分区未知时只做跨分区只读。 */
    data class Qualified(
        val url: String,
        val origin: CoverOrigin,
        val partition: CoverPartition,
    ) : CoverQuery
}

/** 已登记的封面条目: 真实库槽身份 + 位图 + 完整关联 (重复登记已去重)。 */
internal class CoverEntry(
    /** 该记录在库内的真实槽身份 (各端自己的键类型, 对外不解释)。 */
    val slot: Any,
    val bitmap: ImageBitmap,
    val usages: Set<CoverUsage>,
)

/**
 * 封面复用存储: 各端现成有界缓存上的最窄操作。
 *
 * 查询与提交都按真实库槽进行: 候选按完整关联筛出并带上槽身份, 提交/只更新登记/触访问
 * 都作用在被核准的那个槽上, 不按逻辑键猜另一个槽。读取是否改变库内访问序取决于各端实现。
 */
internal interface CoverReuseStore {
    /**
     * 在真实缓存 owner 锁内执行登记决策与提交。
     *
     * 解码主表实现就是表自己的 owner 锁, 与它的 get/put/clear/resize 是同一把;
     * Coil 实现只串行本适配器的用途登记操作, 库自身的引擎写入与内存 LRU 另有自己的锁。
     */
    fun <T> withOwnerLock(block: () -> T): T

    /**
     * 本次登记域 (url, scope) 内该底层图的现有记录; 不在本域返回 null。
     *
     * 带上本次域是为了让库内有界实现只需读本域规范键, 不必扫全库把别的封面预热。
     */
    fun slotOfNative(url: String, scope: CoverScope, native: Any): CoverEntry?

    /** 该真实登记范围下的现有记录 (可能为空)。 */
    fun slotsOf(url: String, scope: CoverScope): List<CoverEntry>

    /**
     * 与该查询相关的候选记录 (按完整关联筛)。
     *
     * 是否改变库内访问序由各端实现与其库能力决定, 不是统一"非变更读取"。
     */
    fun candidates(query: CoverQuery): List<CoverEntry>

    /** 该条目的槽是否就是本范围自己的规范槽 (决定换图时能否就地替换而不丢别的域)。 */
    fun isCanonicalSlot(entry: CoverEntry, url: String, scope: CoverScope): Boolean

    /**
     * 为本范围落一条新记录。
     *
     * @return 是否真的被缓存接收 (单张超上限等按库契约拒收时返回 false)
     */
    fun putNew(url: String, scope: CoverScope, bitmap: ImageBitmap, usages: Set<CoverUsage>): Boolean

    /**
     * 把某条**规范封面槽**里记录的显示复用换成 [bitmap]。
     *
     * 只对规范封面槽调用: 解码约束槽的像素是其约束的原产物, 不在本操作里替换。
     *
     * @return 是否真的换成并被缓存接收
     */
    fun replace(entry: CoverEntry, bitmap: ImageBitmap, usages: Set<CoverUsage>): Boolean

    /** 只更新某个已有槽的完整关联 (不动位图)。 */
    fun updateUsages(entry: CoverEntry, usages: Set<CoverUsage>)

    /** 把真正被选中的那个槽按真实使用刷新访问序。 */
    fun markUsed(entry: CoverEntry)

    /** 清掉本存储里已登记的封面用途条目 (设置页清缓存); 不动库里的普通条目与磁盘内容。 */
    fun clearCoverEntries()

    /** 清掉本存储里的普通图片条目、保留已登记的封面用途条目 (退出阅读只清非封面)。 */
    fun clearNonCoverEntries()
}

/**
 * 封面复用的共用语义: 查询选择与登记提交。
 *
 * 面积比较与提交在同一条窄锁内完成, 网络/解码都在锁外;
 * 同一资源域内更小的图不替代已就绪的更大图, 同面积的新结果照常生效。
 */
internal object CoverReuse {

    /**
     * 按查询范围选当前可复用的最大面积记录, 并把胜出的槽刷成最近使用。
     *
     * 候选读取、胜出判定与刷新访问序都在存储自己的 owner 边界内完成,
     * 不会在中间被并发的登记/清理插进来。
     */
    fun select(store: CoverReuseStore, query: CoverQuery): ImageBitmap? = store.withOwnerLock {
        val picked = store.candidates(query).maxByOrNull { it.area() } ?: return@withOwnerLock null
        store.markUsed(picked)
        picked.bitmap
    }

    /**
     * 登记一份已解出的封面结果。这是四端唯一的封面登记入口。
     *
     * 1) 该底层图在本次范围已有记录 (同一解码结果换包装): 只并入关联, 不存第二份;
     * 2) 该范围已有记录: 更小的不替代更大 (关联仍并入), 同面积/更大则在原槽换图;
     * 3) 都没有: 在该范围的规范槽上新建记录 (同一底层图落在别的范围时各自一条, 由库真实计费)。
     *
     * 不同来源/分区是不同范围, 因此另一范围的旧图不会被本范围登记冒用。
     */
    fun record(
        store: CoverReuseStore,
        bitmap: ImageBitmap,
        url: String,
        scope: CoverScope,
    ) {
        val usage = CoverUsage(url, scope)
        val native = bitmap.resourceIdentity()
        store.withOwnerLock {
            val sameNative = store.slotOfNative(url, scope, native)
            if (sameNative != null) {
                // 该图在本域已有记录: 只并入本次关联, 不存第二份
                if (usage !in sameNative.usages) store.updateUsages(sameNative, sameNative.usages + usage)
                // 本域更小的变体: 关联保留在该变体自己的记录上 (它确实是封面用途),
                // 但显示复用仍按面积选更大的那张, 不做交接
                val candidates = store.slotsOf(url, scope)
                val winner = candidates.maxByOrNull { it.area() }
                if (winner == null || winner.area() <= sameNative.area()) {
                    // 同面积/更大: 本域显示复用交接给这张图, 其它记录不再对这个域作保
                    handOver(store, url, scope, keep = sameNative)
                } else {
                    // 库里还有更大的图 (仍按最大显示): 但它不能替同尺寸的旧记录作保,
                    // 同面积的旧候选必须把本域交给这次的新结果
                    val area = sameNative.area()
                    candidates.filter { it.slot != sameNative.slot && it.area() == area }
                        .forEach { store.updateUsages(it, it.usages - usage) }
                }
                return@withOwnerLock
            }
            val target = store.slotsOf(url, scope).maxByOrNull { it.area() }
            if (target != null) {
                val usages = if (usage in target.usages) target.usages else target.usages + usage
                if (target.area() > bitmap.pixelArea()) {
                    // 本域更小的新结果不顶掉已就绪的大图, 关联仍并入
                    store.updateUsages(target, usages)
                    return@withOwnerLock
                }
                // 本域同面积/更大的新图实际生效; 旧图其它真实域仍留在受限条目里, 不迁给新图
                val others = target.usages - usage
                if (others.isEmpty() && store.isCanonicalSlot(target, url, scope)) {
                    // 这条记录只服务本域且就是本域规范槽: 就地换图, 被拒则维持原图
                    if (!store.replace(target, bitmap, setOf(usage))) store.updateUsages(target, usages)
                    return@withOwnerLock
                }
                // 先把新图真正落库; 没被接收 (如单张超上限) 就维持原图继续服务本域, 不改旧元数据
                val accepted = store.putNew(url, scope, bitmap, setOf(usage))
                if (!accepted) {
                    store.updateUsages(target, usages)
                    return@withOwnerLock
                }
                // 已接收: 原图留在原记录继续服务其它真实域 (不复制像素/不多记账), 本域交给新图
                store.updateUsages(target, others)
                return@withOwnerLock
            }
            store.putNew(url, scope, bitmap, setOf(usage))
        }
    }
}

private fun CoverEntry.area(): Long = bitmap.width.toLong() * bitmap.height

internal fun CoverEntry.matches(query: CoverQuery): Boolean = usages.any { it.matches(query) }

private fun CoverUsage.matches(query: CoverQuery): Boolean = when (query) {
    is CoverQuery.ByUrl -> url == query.url
    is CoverQuery.Qualified -> url == query.url && scope.matches(query)
}

private fun CoverScope.matches(query: CoverQuery.Qualified): Boolean =
    originMatches(query.origin) &&
        (query.partition == CoverPartition.Unknown || partition == query.partition)

/** 未知来源的登记不得冒充已知来源命中; 明确无来源只与同为无来源的查询互认。 */
private fun CoverScope.originMatches(queryOrigin: CoverOrigin): Boolean = when (origin) {
    CoverOrigin.Unknown -> false
    CoverOrigin.None -> queryOrigin == CoverOrigin.None
    is CoverOrigin.Known -> queryOrigin == origin
}

/** 本域显示复用已交给 [keep] 那张图: 其它记录里的同一关联撤掉, 位图与别的域都不动。 */
private fun handOver(
    store: CoverReuseStore,
    url: String,
    scope: CoverScope,
    keep: CoverEntry,
) {
    val usage = CoverUsage(url, scope)
    store.slotsOf(url, scope)
        .filter { it.slot != keep.slot && usage in it.usages }
        .forEach { store.updateUsages(it, it.usages - usage) }
}
