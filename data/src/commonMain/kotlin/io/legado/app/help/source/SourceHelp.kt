package io.legado.app.help.source

import io.legado.app.constant.SourceType
import io.legado.app.help.casUpdate
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.utils.concurrent.newConcurrentMap
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * 书源/音频源 CRUD 与排序维护 (shared commonMain 下沉版)。
 *
 * 原 app 端 SourceHelp 依赖 appDb/ReadBook/AudioPlay/SourceConfig/AppCacheManager,
 * 下沉后改走 provider 间接:
 * - `appDb.xxxDao` → `AppDbProviders.get().xxxDao`
 * - `appDb.runInTransaction { ... }` → `AppDbProviders.get().runInTransaction { ... }`
 * - `ReadBook.bookSource` / `AudioPlay.bookSource` 缓存命中 → [SourceHelpAccessors] provider
 * - `SourceConfig.removeSource(s)` + `AppCacheManager.clearSourceVariables()` 副作用
 *   → [SourceHelpAccessors] provider 的 onBookSourceDeleted/onBookSourcesDeleted
 *
 * 行为与原 app 端完全一致, 仅多一层 provider 间接。app 端调用方 import 不变
 * (跨模块同包名同签名 object 自动合并, 但本类整体下沉, app 端原文件已删除)。
 *
 * 注: 全部方法改为 suspend (DAO 调用为 suspend)。
 * commonMain 中禁止使用 runBlocking (Native 端死锁风险), 故同步入口需由调用方
 * 在协程作用域内调用, 或在 JVM 专属路径 (如 app 端 Glide 同步回调) 自行 runBlocking。
 */
object SourceHelp {

    /**
     * 正在进行的按 key 查源单飞合并表: 解决列表/首屏多图并发加载时针对同一个 origin 重复打库的问题。
     * 完成即从表中移除, 内存零常驻, 零陈旧风险。
     */
    private val inFlightLock = SynchronizedObject()
    private val inFlight = newConcurrentMap<String, CompletableDeferred<BaseSource?>>()

    /**
     * 书源对象短时内存缓存 (修原版缺陷 #1「每次封面 fetch 都同步查一次 book_sources 宽行」)。
     *
     * 原版 [getSource] 只有 ReadBook/AudioPlay 两个 in-flight 引用兜底, 列表页 N 张不同 URL 的封面
     * = N 次 `select * from book_sources`(整行含全部规则字段) + N 次 loginHeader 查库;
     * singleFlight 只合并**并发**批次, 滚动产生的新批次仍逐图回库。
     * 本层把「逐图」降为「逐时间窗」: 窗口内同 origin 只查一次库。
     *
     * 陈旧代价被刻意压到 [CACHE_TTL_MS] (远小于一次人工「改书源 header → 回来看封面」的间隔),
     * 且由 [evict]/[evictAll] 按失效代号即时作废兜底 —— 缓存只缓存**实体行**,
     * 不缓存 header 解析结果与 cookie, 所以时效性签名/登录态每次仍现取, 不受本层影响。
     *
     * 已接上显式失效的入口: 书源编辑保存 / 列表整行重写 / 删除 / 逐源启停 ([enableSource]) /
     * 批量导入 / 恢复备份 / WebApi 改源。列表页批量启停、改分组、换源与探索页刷新这些
     * 只动 enabled/groups/weight/lastCheckTime 的整行写不挂失效: 封面链路不读这些字段,
     * 由 [CACHE_TTL_MS] 兜住。
     */
    private const val CACHE_CAPACITY = 64
    private const val CACHE_TTL_MS = 15_000L

    /**
     * 失效代号: 任何 [evict]/[evictAll] 都自增。
     *
     * 为何不是「删了了事」: 查库与写入之间有时间窗口 —— A 协程查库期间用户改了源并 [evict],
     * A 随后把**改之前**的旧行发布回表中, 光靠先删后写挡不住。现在条目带着写入时的代号,
     * 读时代号不符即视为脏行丢弃, 在途写入 [cachePut] 也不再发布, 两头都封住。
     * 代价: 单 key 失效会把整张 (≤[CACHE_CAPACITY]) 表作废 —— 失效只发生在人工改源/导入
     * 这种稀疏时刻, 多一次查库换「确定不读脏数据」, 划算。
     */
    private val epoch = atomic(0L)

    private fun currentEpoch(): Long = epoch.value

    /** key → 结果快照。null 结果同样缓存 (源不存在也是稳定答案, 防穿透)。 */
    private class CacheEntry(val at: Long, val epoch: Long, val source: BaseSource?)

    /**
     * **不可变快照 + CAS 写 (copy-on-write)**, 不是锁表。
     *
     * 本表在图片加载热路径上: 每张封面取源都要读一次, 首屏几十张并发会同时进来。
     * 锁表 (包括手写 `synchronized` 与 [io.legado.app.help.CommonLruCache]) 有两个不可接受的成本:
     * 1. 读也要抢进程级重量锁 —— 把本该并行的 N 张图串成一条队;
     * 2. LRU 的 accessOrder 要求**每读一次就改一次结构**, 等于把读降级成写。
     * 而写入极稀 (每 origin 每 15s 最多一次), 所以把成本全倒向写侧:
     * 读 = 一次 volatile 取快照 + 一次 map 查 (无锁、无分配),
     * 写 = CAS 重试环里生成新 map (≤[CACHE_CAPACITY] 项)。
     * 淘汰改用「按写入时刻淘汰最旧」, 不再需要访问序 → 读路径不再写表。
     *
     * 平台安全性: [kotlinx.atomicfu.AtomicRef] 在 JVM/Android 是 volatile+CAS,
     * 在 Kotlin/Native (iOS/鸿蒙) 是新内存模型下真正可用的原子引用;
     * 不用 [io.legado.app.utils.concurrent.newConcurrentMap] 的原因 (已核对 nativeMain actual):
     * 它在 native 上是 atomicfu SynchronizedObject 包装, **每个操作 (含 get) 都进一把实例锁**
     * —— 线程安全, 但会把并行读串行化; 而且它是可变映射, 要维护 LRU 访问序就必须读时改表。
     * 本表读远多于写, 所以选 CoW。
     */
    private val cacheRef = atomic<Map<String, CacheEntry>>(emptyMap())

    /** 命中且未过期、且失效代号相符才返回 entry (其 source 可能为 null = 已确认该源不存在)。 */
    private fun cacheLookup(key: String): CacheEntry? {
        val entry = cacheRef.value[key] ?: return null
        if (entry.epoch != currentEpoch() ||
            systemCurrentTimeMillis() - entry.at > CACHE_TTL_MS
        ) {
            // 只摘除自己读到的这一条, 不碰并发间别人刚写入的新值
            cacheRef.casUpdate { cur -> if (cur[key] === entry) cur - key else cur }
            return null
        }
        return entry
    }

    /**
     * 发布一条缓存。[readEpoch] 必须是**查库前**取的失效代号:
     * 查库期间发生过任何失效, 刚读到的行已经不可信, 直接不发布 (下一个读者重新查)。
     */
    private fun cachePut(key: String, source: BaseSource?, readEpoch: Long) {
        if (readEpoch != currentEpoch()) return
        val entry = CacheEntry(systemCurrentTimeMillis(), readEpoch, source)
        cacheRef.casUpdate { cur ->
            if (!cur.containsKey(key) && cur.size >= CACHE_CAPACITY) {
                val oldest = cur.minByOrNull { it.value.at }
                (if (oldest == null) cur else cur - oldest.key) + (key to entry)
            } else {
                cur + (key to entry)
            }
        }
    }

    /**
     * 失效单个书源缓存。所有「会改变 book_sources 行内容」的写入口都应调用
     * (书源编辑保存 / 启停 / 分组变更 / 删除 / 导入 / 恢复备份)。
     */
    fun evict(key: String?) {
        key ?: return
        epoch.incrementAndGet()
        cacheRef.casUpdate { if (it.containsKey(key)) it - key else it }
    }

    /** 全量失效 (批量导入 / 恢复备份等无法逐 key 枚举的写路径)。 */
    fun evictAll() {
        epoch.incrementAndGet()
        cacheRef.casUpdate { emptyMap() }
    }

    private suspend fun singleFlight(
        key: String,
        block: suspend () -> BaseSource?,
    ): BaseSource? {
        var isLeader = false
        val deferred = synchronized(inFlightLock) {
            inFlight.getOrPut(key) {
                isLeader = true
                CompletableDeferred()
            }
        }
        if (!isLeader) {
            return try {
                deferred.await()
            } catch (e: CancellationException) {
                // 区分「自己所在协程被取消」与「leader 被取消连坐」: 后者必须自己查一次,
                // 不能因另一张图的退页把这张无关封面的加载也静默取消掉。
                if (currentCoroutineContext().isActive) block() else throw e
            }
        }
        try {
            val result = block()
            deferred.complete(result)
            return result
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            synchronized(inFlightLock) {
                inFlight.remove(key)
            }
        }
    }

    suspend fun getSource(key: String?): BaseSource? {
        key ?: return null
        // 优先返回当前阅读/播放书籍的书源缓存实例 (对应原 ReadBook.bookSource / AudioPlay.bookSource)
        SourceHelpAccessors.get().getCachedReadingBookSource(key)?.let { return it }
        SourceHelpAccessors.get().getCachedAudioBookSource(key)?.let { return it }
        // 短时缓存命中即零查库 (封面/正文多图逐张加载的主路径, 见 cache 注释)
        cacheLookup(key)?.let { return it.source }
        return singleFlight(key) {
            cacheLookup(key)?.let { return@singleFlight it.source }
            // 查库**前**取代号, 查库期间发生过失效则不发布 (见 cachePut)
            val readEpoch = currentEpoch()
            AppDbProviders.get().bookSourceDao.getBookSource(key)
                .also { cachePut(key, it, readEpoch) }
        }
    }

    suspend fun getSource(key: String?, type: Int): BaseSource? {
        key ?: return null
        return when (type) {
            SourceType.book, SourceType.rss -> getSource(key)
            SourceType.tts -> AppDbProviders.get().httpTTSDao.get(
                key.substringAfter("httpTts:").toLongOrNull() ?: -1
            )
            else -> null
        }
    }

    suspend fun deleteSource(key: String, type: Int) {
        when (type) {
            SourceType.book, SourceType.rss -> deleteBookSource(key)
            SourceType.tts -> {
                AppDbProviders.get().httpTTSDao.get(
                    key.substringAfter("httpTts:").toLongOrNull() ?: -1
                )?.let {
                    AppDbProviders.get().httpTTSDao.delete(it)
                }
            }
        }
    }

    suspend fun deleteBookSourceParts(sources: List<BookSourcePart>) {
        if (sources.isEmpty()) return
        val keys = sources.map { it.bookSourceUrl }
        deleteBookSourcesByKeys(sources = sources, keys = keys)
    }

    suspend fun deleteBookSources(sources: List<BookSource>) {
        if (sources.isEmpty()) return
        val keys = sources.map { it.bookSourceUrl }
        deleteBookSourcesByKeys(keys = keys)
    }

    private suspend fun deleteBookSourcesByKeys(
        sources: List<BookSourcePart>? = null,
        keys: List<String>
    ) {
        AppDbProviders.get().runInTransactionSuspending {
            if (sources != null) {
                AppDbProviders.get().bookSourceDao.delete(sources)
            } else {
                // chunked 返回 List, forEach 是 inline 允许 suspend 调用 (chunked 的 transform lambda 非 suspend)
                keys.chunked(999).forEach { AppDbProviders.get().bookSourceDao.deleteIn(it) }
            }
            // deleteSourceVariables 含 LIKE 'v_KEY_%'，无法折叠成 IN 批量；逐键执行，但仍包在外层事务里
            keys.forEach { AppDbProviders.get().cacheDao.deleteSourceVariables(it) }
        }
        // 对应原 SourceConfig.removeSources(keys) + AppCacheManager.clearSourceVariables()
        SourceHelpAccessors.get().onBookSourcesDeleted(keys)
        // 行已不存在, 缓存必须同步抹掉 (否则 TTL 窗口内仍会拿到旧 header/coverDecodeJs)
        keys.forEach { evict(it) }
    }

    private suspend fun deleteBookSourceInternal(key: String) {
        AppDbProviders.get().bookSourceDao.delete(key)
        AppDbProviders.get().cacheDao.deleteSourceVariables(key)
        // 对应原 SourceConfig.removeSource(key)
        SourceHelpAccessors.get().onBookSourceDeleted(key)
        evict(key)
    }

    suspend fun deleteBookSource(key: String) {
        deleteBookSourceInternal(key)
        // 对应原 AppCacheManager.clearSourceVariables() (onBookSourceDeleted 已含,
        // 但原 deleteBookSource 调用 Internal 后再调 clearSourceVariables, 与 deleteBookSourcesByKeys
        // 在事务后统一调 onBookSourcesDeleted 含 clearSourceVariables 的语义一致,
        // 这里 onBookSourceDeleted 已涵盖, 无需重复调用)
    }

    suspend fun enableSource(key: String, type: Int, enable: Boolean) {
        when (type) {
            SourceType.book, SourceType.rss -> {
                AppDbProviders.get().bookSourceDao.enable(key, enable)
                evict(key)
            }
            SourceType.tts -> Unit
        }
    }

    /**
     * 调整排序序号
     */
    suspend fun adjustSortNumber() {
        val dao = AppDbProviders.get().bookSourceDao
        val max = dao.maxOrder()
        val min = dao.minOrder()
        val rangeOverflow = max > 99999 || min < -99999
        val hasDup = dao.hasDuplicateOrder()
        if (!rangeOverflow && !hasDup) return

        // 快路径：仅绝对值越界，但 max-min 还能塞进 [-99999, 99999]。
        // 一条 UPDATE 整体平移就能修好，免去加载并回写每个 BookSourcePart。
        if (!hasDup && (max - min) <= 199998) {
            dao.shiftCustomOrder(-((max + min) / 2))
            return
        }

        // 兜底：全量重排为 0..N-1
        val sources = dao.allPart()
        sources.forEachIndexed { index, bookSource ->
            bookSource.customOrder = index
        }
        dao.upOrder(sources)
    }

}
