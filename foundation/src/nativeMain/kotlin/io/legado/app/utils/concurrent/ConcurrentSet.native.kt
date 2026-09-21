package io.legado.app.utils.concurrent

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * `newConcurrentSet` 的 iOS/鸿蒙 actual 实现 (nativeMain 中间源集共用)。
 *
 * Kotlin/Native 无 `java.util.concurrent.ConcurrentHashMap`, 用 atomicfu
 * [SynchronizedObject] + `synchronized` 包一层, 读写全部进同一把锁, 实现真线程安全;
 * 迭代返回快照副本, 避免边遍历边增删时抛并发修改异常。
 *
 * 前提说明: IoDispatcher 的 native actual 是 Dispatchers.Default (真多线程池,
 * 见 ThreadPoolDispatchers.native.kt), 旧注释"Dispatchers.Default 在 Kotlin/Native
 * 上为单线程调度"不成立, SearchViewModel.bookshelf 等跨协程读写是实际场景,
 * 空壳 mutableSetOf 不再满足需求。
 */
actual fun <T> newConcurrentSet(): MutableSet<T> {
    val lock = SynchronizedObject()
    val delegate = mutableSetOf<T>()
    return object : MutableSet<T> {
        override val size: Int get() = synchronized(lock) { delegate.size }
        override fun isEmpty(): Boolean = synchronized(lock) { delegate.isEmpty() }
        override fun contains(element: T): Boolean = synchronized(lock) { delegate.contains(element) }
        override fun containsAll(elements: Collection<T>): Boolean = synchronized(lock) { delegate.containsAll(elements) }
        override fun iterator(): MutableIterator<T> = synchronized(lock) { delegate.toMutableSet().iterator() }
        override fun add(element: T): Boolean = synchronized(lock) { delegate.add(element) }
        override fun remove(element: T): Boolean = synchronized(lock) { delegate.remove(element) }
        override fun addAll(elements: Collection<T>): Boolean = synchronized(lock) { delegate.addAll(elements) }
        override fun removeAll(elements: Collection<T>): Boolean = synchronized(lock) { delegate.removeAll(elements) }
        override fun retainAll(elements: Collection<T>): Boolean = synchronized(lock) { delegate.retainAll(elements) }
        override fun clear() = synchronized(lock) { delegate.clear() }
    }
}
