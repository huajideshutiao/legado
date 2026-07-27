package io.legado.app.utils.objectpool

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// 纯 Kotlin 对象池, 替代 androidx.core.util.Pools (commonMain 不可用)

interface Pool<T> {

    fun acquire(): T?

    fun release(instance: T): Boolean

}

// 非线程安全, 用 ArrayDeque 复刻 androidx SimplePool 语义 (满容量 release 返回 false, 重复 release 抛异常)
open class SimplePool<T : Any>(private val maxPoolSize: Int) : Pool<T> {

    private val deque = ArrayDeque<T>(maxPoolSize)

    override fun acquire(): T? = deque.removeFirstOrNull()

    override fun release(instance: T): Boolean {
        if (deque.size >= maxPoolSize) return false
        if (deque.contains(instance)) {
            throw IllegalStateException("Already in the pool!")
        }
        deque.addLast(instance)
        return true
    }

}

// 线程安全版, 委托 SimplePool + atomicfu 锁 (Native target 兼容)
class SynchronizedPool<T : Any>(maxPoolSize: Int) : Pool<T> {

    private val delegate = SimplePool<T>(maxPoolSize)
    private val lock = SynchronizedObject()

    override fun acquire(): T? = synchronized(lock) { delegate.acquire() }

    override fun release(instance: T): Boolean = synchronized(lock) { delegate.release(instance) }

}
