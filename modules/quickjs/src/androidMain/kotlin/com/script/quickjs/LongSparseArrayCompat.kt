package com.script.quickjs

import androidx.collection.LongSparseArray
import androidx.collection.LruCache

/**
 * Android actual: 委托到 androidx.collection 实现, 性能与原行为完全一致。
 *
 * 注: 不能用 `actual typealias` 因为 LongSparseArray 是 final class 无法继承,
 * 改用组合委托模式 (delegate), 暴露与 expect 一致的 API 子集。
 */
actual class LongSparseArrayCompat<T> actual constructor() {
    private val delegate = LongSparseArray<T>()

    actual fun put(key: Long, value: T) {
        delegate.put(key, value)
    }

    actual operator fun get(key: Long): T? = delegate.get(key)

    actual fun remove(key: Long) {
        delegate.remove(key)
    }

    actual fun clear() {
        delegate.clear()
    }
}

actual class LruCacheCompat<K : Any, V : Any> actual constructor(maxSize: Int) {
    private val delegate = LruCache<K, V>(maxSize)

    actual operator fun get(key: K): V? = delegate.get(key)

    actual fun put(key: K, value: V) {
        // androidx.collection.LruCache.put 返回旧值 V?, expect 声明返回 Unit, 显式忽略
        delegate.put(key, value)
    }
}
