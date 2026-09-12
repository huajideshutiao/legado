package io.legado.app.help.image

import io.legado.app.help.casUpdate
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.atomicfu.atomic

/**
 * 图片链「失败 url 跳过表」(对照原版 Glide `OkHttpStreamFetcher.companion failUrl`)。
 *
 * 原版语义: 非 2xx 响应 / 封面二次解密失败的 url 进表, 之后同 url 一律直接失败,
 * 直到进程结束 —— 一次偶发 403/限流就让该封面在整个进程生命周期内不再显示, 且表本身
 * 是无容量上限、无同步的裸 `HashSet`。
 * 本实现在保住「死链不反复打网络」这一原意的前提下修掉三点:
 * - [TTL_MS] 后允许重试 (403/5xx/限流绝大多数是暂时性的);
 * - [CAPACITY] 条上界, 不再无界增长;
 * - 读写跨线程安全 (原版裸 HashSet 在 OkHttp 回调线程写、Glide/Coil 线程读)。
 *
 * key 由各调用方自决 (Coil 网络守卫用规范化后的 request.url; 自下载链路用 origin+url 复合 key),
 * 表只负责"存 / 过期 / 淘汰", 不同维度的 key 互不相干。
 *
 * # 为什么不是锁表 / 不是 [io.legado.app.help.CommonLruCache]
 * 本表的**读**发生在每次图片网络请求前 (首屏几十张封面并发即几十次读), 而**写**只在
 * 请求失败时发生。进程级重量锁会把本该并行的读串成一条队; LRU 的 accessOrder 还要求
 * 每读一次就改一次结构 (读降级成写)。故成本全部倒向写侧:
 * 读 = 一次 volatile 取快照 + 一次 map 查 (无锁、无分配); 写 = [casUpdate] 的 CAS 环内
 * 生成新 map (≤[CAPACITY] 项)。淘汰按「最早过期」而非访问序, 读路径因此完全不改表。
 *
 * 只用实测存在的原子 API: `AtomicRef.value` / `AtomicRef.compareAndSet`
 * (javap 确认 `AtomicRef.update{}` 并非该类成员, 不凭记忆使用)。
 * 不改用 [io.legado.app.utils.concurrent.newConcurrentMap] 的原因 (已核对 nativeMain actual):
 * 它在 native 上是 SynchronizedObject 包装 —— 线程安全, 但**含 get 在内的每个操作都进锁**,
 * 而本表的读就在每次图片请求前; CoW 让读侧连这一把锁也不需要。
 */
private const val CAPACITY = 512

/** 失败记录的存活时长: 到点后该 url 重新允许发起网络请求。 */
private const val TTL_MS = 10 * 60 * 1000L

/** key → 过期时刻 (epoch millis)。不可变快照, 写时整体替换。 */
private val failedRef = atomic<Map<String, Long>>(emptyMap())

/** 该 key 是否仍在失败期内 (过期即视为可重试, 并顺手摘除)。 */
fun isImageLoadFailed(key: String): Boolean {
    val deadline = failedRef.value[key] ?: return false
    if (systemCurrentTimeMillis() > deadline) {
        // 只摘自己读到的这一条, 不覆盖并发间别人刚写入的新 deadline
        failedRef.casUpdate { cur -> if (cur[key] == deadline) cur - key else cur }
        return false
    }
    return true
}

/** 记一次失败 (重复记录刷新过期时刻, 等价于"最近又失败了一次")。 */
fun markImageLoadFailed(key: String) {
    val deadline = systemCurrentTimeMillis() + TTL_MS
    failedRef.casUpdate { cur ->
        if (!cur.containsKey(key) && cur.size >= CAPACITY) {
            val soonestToExpire = cur.minByOrNull { it.value }
            (if (soonestToExpire == null) cur else cur - soonestToExpire.key) + (key to deadline)
        } else {
            cur + (key to deadline)
        }
    }
}

/**
 * 清空失败表。
 *
 * 用于「死链前提已变」的场合: 清除封面缓存 (给用户重试的机会)、书源批量变更后
 * (换 header / 换 cookie 后原本 403 的链接可能就能开了)。
 */
fun clearImageLoadFailures() {
    failedRef.value = emptyMap()
}
