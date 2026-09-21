package io.legado.app.utils.objectpool

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

class ObjectPoolLocked<T>(private val delegate: ObjectPool<T>) : ObjectPool<T> by delegate {

    private val lock = SynchronizedObject()

    override fun obtain(): T = synchronized(lock) {
        delegate.obtain()
    }

    override fun recycle(target: T) = synchronized(lock) {
        delegate.recycle(target)
    }

}
