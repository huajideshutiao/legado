package com.script.quickjs

import java.util.LinkedHashMap

/**
 * 桌面 JVM actual: 用 java.util.HashMap<Long, T> 简化实现。
 *
 * 不追求 androidx.collection 的内存紧凑 (binary search array layout),
 * 桌面 JVM 内存充裕, 语义等价即可。所有方法非线程安全, 调用方需自行 synchronized
 * (与 Android 实现行为对齐: LongSparseArray 本身也非线程安全)。
 */
actual class LongSparseArrayCompat<T> actual constructor() {
    private val delegate = HashMap<Long, T>()

    actual fun put(key: Long, value: T) {
        delegate[key] = value
    }

    actual operator fun get(key: Long): T? = delegate[key]

    actual fun remove(key: Long) {
        delegate.remove(key)
    }

    actual fun clear() = delegate.clear()
}

/**
 * 桌面 JVM actual: 用 LinkedHashMap accessOrder=true 实现 LRU。
 *
 * removeEldestEntry 在 size > maxSize 时淘汰最旧条目, 行为与 androidx.collection.LruCache 一致。
 */
actual class LruCacheCompat<K : Any, V : Any> actual constructor(private val maxSize: Int) {
    private val delegate = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
            return size > maxSize
        }
    }

    actual operator fun get(key: K): V? = delegate[key]

    actual fun put(key: K, value: V) {
        delegate[key] = value
    }
}
