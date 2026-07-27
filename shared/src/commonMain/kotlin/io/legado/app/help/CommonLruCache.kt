package io.legado.app.help

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * commonMain 纯 Kotlin 实现的线程安全 LruCache。
 *
 * 替代 app 端 `androidx.collection.LruCache`，供 commonMain 的 [CacheManager] 自持
 * 内存 LruCache 使用。LinkedHashMap(accessOrder=true) 实现 LRU 顺序，synchronized 保证
 * 线程安全（与 androidx.collection.LruCache 内部 synchronized 行为一致）。
 *
 * 支持 sizeOf 估算函数：按字节大小裁剪（对应原 `LruCache.sizeOf` 重写估算内存大小），
 * 而非按 entry 数量。默认 sizeOf 返回 1（按 entry 数量裁剪）。
 *
 * 注：与 androidx.collection.LruCache 不同点
 * - 没有 trimToSize 的回调通知（entryRemoved），CacheManager 不使用此回调，故省略。
 * - snapshot() 返回 LinkedHashMap.toMap() 副本，遍历过程中删除原 map 是安全的
 *   （对应 AppCacheManager.clearSourceVariables 的遍历删除模式）。
 */
class CommonLruCache<K, V>(
    private val maxSize: Int,
    private val sizeOf: (K, V) -> Int = { _, _ -> 1 }
) {
    private val lock = SynchronizedObject()

    // JVM 三参 accessOrder=true 构造器非 common API; get/put 手动 remove+重插移到末尾,
    // 迭代顺序仍为 LRU(最久未使用) -> MRU(最近使用)
    private val map = LinkedHashMap<K, V>(16, 0.75f)
    private var size = 0

    fun put(key: K, value: V): V? = synchronized(lock) {
        val oldValue = map.remove(key)
        if (oldValue != null) {
            size -= sizeOf(key, oldValue)
        }
        map[key] = value
        size += sizeOf(key, value)
        trimToSize()
        oldValue
    }

    operator fun get(key: K): V? = synchronized(lock) {
        // remove+重插将 entry 移到末尾 (LRU 更新, 等价 JVM accessOrder=true 的 get)
        val value = map.remove(key) ?: return null
        map[key] = value
        value
    }

    fun remove(key: K): V? = synchronized(lock) {
        val oldValue = map.remove(key)
        if (oldValue != null) {
            size -= sizeOf(key, oldValue)
        }
        oldValue
    }

    /** 返回当前 map 的只读快照副本, 遍历期间对原 cache 的增删不影响迭代。 */
    fun snapshot(): Map<K, V> = synchronized(lock) { map.toMap() }

    /** 返回当前已用大小 (按 sizeOf 累加)。 */
    fun size(): Int = synchronized(lock) { size }

    private fun trimToSize() {
        val iter = map.iterator()
        while (size > maxSize && iter.hasNext()) {
            val (k, v) = iter.next()
            iter.remove()
            size -= sizeOf(k, v)
        }
    }
}
