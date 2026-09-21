package io.legado.app.utils.concurrent

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * `newConcurrentMap` 的 iOS/鸿蒙 actual 实现 (nativeMain 中间源集共用)。
 *
 * Kotlin/Native 无 `java.util.concurrent.ConcurrentHashMap`, 用 atomicfu
 * [SynchronizedObject] + `synchronized` 包一层, 读写全部进同一把锁, 实现真线程安全;
 * 迭代面 (keys/values/entries) 返回快照副本, 避免边遍历边增删时抛并发修改异常。
 *
 * 前提说明: IoDispatcher 的 native actual 是 Dispatchers.Default (真多线程池,
 * 见 ThreadPoolDispatchers.native.kt), 调用方 (CacheBookShared/ReadBookShared 等)
 * 存在跨线程"边遍历边增删"用法, 旧注释"Kotlin/Native 单线程调度"不成立,
 * 空壳 mutableMapOf 不再满足需求。
 */
actual fun <K, V> newConcurrentMap(): MutableMap<K, V> {
    val lock = SynchronizedObject()
    val delegate = mutableMapOf<K, V>()
    return object : MutableMap<K, V> {
        override val size: Int get() = synchronized(lock) { delegate.size }
        override fun isEmpty(): Boolean = synchronized(lock) { delegate.isEmpty() }
        override fun containsKey(key: K): Boolean = synchronized(lock) { delegate.containsKey(key) }
        override fun containsValue(value: V): Boolean = synchronized(lock) { delegate.containsValue(value) }
        override fun get(key: K): V? = synchronized(lock) { delegate[key] }
        override val keys: MutableSet<K>
            get() = synchronized(lock) { delegate.keys.toMutableSet() }
        override val values: MutableCollection<V>
            get() = synchronized(lock) { delegate.values.toMutableList() }
        override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
            get() = synchronized(lock) { delegate.entries.toMutableSet() }
        override fun put(key: K, value: V): V? = synchronized(lock) { delegate.put(key, value) }
        override fun remove(key: K): V? = synchronized(lock) { delegate.remove(key) }
        override fun putAll(from: Map<out K, V>) = synchronized(lock) { delegate.putAll(from) }
        override fun clear() = synchronized(lock) { delegate.clear() }
    }
}
