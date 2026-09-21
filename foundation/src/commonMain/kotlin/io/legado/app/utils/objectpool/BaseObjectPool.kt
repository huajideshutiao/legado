package io.legado.app.utils.objectpool

abstract class BaseObjectPool<T : Any>(size: Int) : ObjectPool<T> {

    open val pool: Pool<T> = SimplePool(size)

    override fun obtain(): T {
        return pool.acquire() ?: create()
    }

    override fun recycle(target: T) {
        pool.release(target)
    }

}
