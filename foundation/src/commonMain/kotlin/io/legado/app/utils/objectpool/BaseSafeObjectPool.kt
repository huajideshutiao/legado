package io.legado.app.utils.objectpool

abstract class BaseSafeObjectPool<T : Any>(size: Int) : BaseObjectPool<T>(size) {

    override val pool: Pool<T> = SynchronizedPool(size)

}
