package io.legado.app.utils.concurrent

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * entries 迭代面的快照条目: 独立持有 key-value 内容, 与原 map 的任何内部结构无关联。
 *
 * CPF fork 工具链 (kotlin-ohos) 的 override 检查不接受 var 属性桥接 MutableEntry,
 * [setValue] 必须显式实现, 契约同标准库: 返回旧值。
 */
private class SnapshotEntry<K, V>(
    override val key: K,
    value: V
) : MutableMap.MutableEntry<K, V> {
    private var current: V = value

    override val value: V get() = current

    override fun setValue(newValue: V): V {
        val old = current
        current = newValue
        return old
    }
}

/**
 * `newConcurrentMap` 的 iOS/鸿蒙 actual 实现 (nativeMain 中间源集共用)。
 *
 * Kotlin/Native 无 `java.util.concurrent.ConcurrentHashMap`, 用 atomicfu
 * [SynchronizedObject] + `synchronized` 包一层, 读写全部进同一把锁, 实现真线程安全;
 * 迭代面 (keys/values/entries) 返回快照副本, 对齐 JVM 端 ConcurrentHashMap 的
 * 弱一致迭代契约 (遍历不抛并发修改异常, 看到的是创建迭代器时点的状态);
 * 快照集合与原 map 无关联, 对快照的结构修改不写回原 map。
 *
 * entries 必须逐条拷贝成 [SnapshotEntry] (内容快照): Kotlin/Native stdlib 的
 * HashMap.EntryRef 在取值时校验原 map 的结构修改计数 (fail-fast), EntryRef 引用
 * 即使装进新集合也仍与原 map 耦合, 原 map 在遍历期间被其他线程增删依然会抛
 * ConcurrentModificationException。
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
            get() = synchronized(lock) {
                delegate.entries.mapTo(LinkedHashSet()) { SnapshotEntry(it.key, it.value) }
            }
        override fun put(key: K, value: V): V? = synchronized(lock) { delegate.put(key, value) }
        override fun remove(key: K): V? = synchronized(lock) { delegate.remove(key) }
        override fun putAll(from: Map<out K, V>) = synchronized(lock) { delegate.putAll(from) }
        override fun clear() = synchronized(lock) { delegate.clear() }
    }
}
