package io.legado.app.utils.concurrent

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * `newSynchronizedList` 的 iOS/鸿蒙 actual 实现 (nativeMain 中间源集共用)。
 *
 * Kotlin/Native 无 `java.util.Collections.synchronizedList`, 用 atomicfu
 * [SynchronizedObject] + `synchronized` 包一层, 读写全部进同一把锁, 实现真线程安全
 * (对齐 jvmAndAndroidMain Collections.synchronizedList 语义);
 * 迭代 (iterator/listIterator) 与 subList 返回快照副本, 避免边遍历边增删时抛并发修改异常
 * (subList 快照的修改不回流原列表, 调用方当前无此依赖)。
 *
 * 前提说明: IoDispatcher 的 native actual 是 Dispatchers.Default (真多线程池,
 * 见 ThreadPoolDispatchers.native.kt), 调用方 (ChangeCoverViewModelShared 等)
 * 在 callbackFlow 内由多协程并发 add/clear/iterate, 旧注释"直接返回原 delegate"
 * 不成立, 空壳返回不再满足需求。
 */
actual fun <T> newSynchronizedList(delegate: MutableList<T>): MutableList<T> {
    val lock = SynchronizedObject()
    return object : MutableList<T> {
        override val size: Int get() = synchronized(lock) { delegate.size }
        override fun isEmpty(): Boolean = synchronized(lock) { delegate.isEmpty() }
        override fun contains(element: T): Boolean = synchronized(lock) { delegate.contains(element) }
        override fun containsAll(elements: Collection<T>): Boolean = synchronized(lock) { delegate.containsAll(elements) }
        override fun get(index: Int): T = synchronized(lock) { delegate[index] }
        override fun set(index: Int, element: T): T = synchronized(lock) { delegate.set(index, element) }
        override fun add(element: T): Boolean = synchronized(lock) { delegate.add(element) }
        override fun add(index: Int, element: T) = synchronized(lock) { delegate.add(index, element) }
        override fun addAll(elements: Collection<T>): Boolean = synchronized(lock) { delegate.addAll(elements) }
        override fun addAll(index: Int, elements: Collection<T>): Boolean = synchronized(lock) { delegate.addAll(index, elements) }
        override fun remove(element: T): Boolean = synchronized(lock) { delegate.remove(element) }
        override fun removeAt(index: Int): T = synchronized(lock) { delegate.removeAt(index) }
        override fun removeAll(elements: Collection<T>): Boolean = synchronized(lock) { delegate.removeAll(elements) }
        override fun retainAll(elements: Collection<T>): Boolean = synchronized(lock) { delegate.retainAll(elements) }
        override fun clear() = synchronized(lock) { delegate.clear() }
        override fun indexOf(element: T): Int = synchronized(lock) { delegate.indexOf(element) }
        override fun lastIndexOf(element: T): Int = synchronized(lock) { delegate.lastIndexOf(element) }
        override fun iterator(): MutableIterator<T> = synchronized(lock) { delegate.toMutableList().iterator() }
        override fun listIterator(): MutableListIterator<T> = synchronized(lock) { delegate.toMutableList().listIterator() }
        override fun listIterator(index: Int): MutableListIterator<T> = synchronized(lock) { delegate.toMutableList().listIterator(index) }
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> =
            synchronized(lock) { delegate.subList(fromIndex, toIndex).toMutableList() }
    }
}
