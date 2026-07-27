package com.script.quickjs

/**
 * 跨平台 Long->T 映射 (KP1.1)。
 *
 * Android 用 `androidx.collection.LongSparseArray` (基于 binary search, 内存紧凑);
 * 桌面 JVM 用 `java.util.HashMap<Long, T>` 简化实现 (语义等价, 不追求极致内存)。
 *
 * 仅暴露当前代码用到的 API: put/get/remove/clear, 避免过度抽象。
 */
expect class LongSparseArrayCompat<T>() {
    fun put(key: Long, value: T)
    operator fun get(key: Long): T?
    fun remove(key: Long)
    fun clear()
}

/**
 * 跨平台 LRU 缓存 (KP1.1)。
 *
 * Android 用 `androidx.collection.LruCache` (基于 LinkedHashMap accessOrder);
 * 桌面 JVM 用同样的 LinkedHashMap 实现, 行为一致。
 *
 * K/V 上界 Any: 对齐 androidx.collection.LruCache 的泛型约束。
 */
expect class LruCacheCompat<K : Any, V : Any>(maxSize: Int) {
    operator fun get(key: K): V?
    fun put(key: K, value: V)
}
