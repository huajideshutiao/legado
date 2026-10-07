package org.jsoup.internal

/**
 * 见 [ElementsBase]: native 侧 stdlib 的 ArrayList 是 final, 集合成员转发到内部 ArrayList 实现同一接口面。
 */
public actual open class ElementsBase<E> : MutableList<E> {

    private val delegate: MutableList<E> = ArrayList()

    public actual constructor()

    /** stdlib 的 ArrayList 无容量构造, 容量提示在此平台无对应实现。 */
    public actual constructor(initialCapacity: Int) : this()

    public actual constructor(elements: Collection<E>) {
        delegate.addAll(elements)
    }

    public actual constructor(elements: List<E>) {
        delegate.addAll(elements)
    }

    public actual constructor(vararg elements: E) {
        delegate.addAll(elements)
    }

    public actual override val size: Int get() = delegate.size

    public actual override fun isEmpty(): Boolean = delegate.isEmpty()

    public actual override fun contains(element: E): Boolean = delegate.contains(element)

    public actual override fun iterator(): MutableIterator<E> = delegate.iterator()

    public actual override fun containsAll(elements: Collection<E>): Boolean = delegate.containsAll(elements)

    public actual override fun get(index: Int): E = delegate.get(index)

    public actual override fun indexOf(element: E): Int = delegate.indexOf(element)

    public actual override fun lastIndexOf(element: E): Int = delegate.lastIndexOf(element)

    public actual override fun listIterator(): MutableListIterator<E> = delegate.listIterator()

    public actual override fun listIterator(index: Int): MutableListIterator<E> = delegate.listIterator(index)

    public actual override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> = delegate.subList(fromIndex, toIndex)

    public actual override fun add(element: E): Boolean = delegate.add(element)

    public actual override fun add(index: Int, element: E): Unit = delegate.add(index, element)

    public actual override fun remove(element: E): Boolean = delegate.remove(element)

    public actual override fun removeAt(index: Int): E = delegate.removeAt(index)

    public actual override fun addAll(elements: Collection<E>): Boolean = delegate.addAll(elements)

    public actual override fun addAll(index: Int, elements: Collection<E>): Boolean = delegate.addAll(index, elements)

    public actual override fun removeAll(elements: Collection<E>): Boolean = delegate.removeAll(elements)

    public actual override fun retainAll(elements: Collection<E>): Boolean = delegate.retainAll(elements)

    public actual override fun clear(): Unit = delegate.clear()

    public actual override fun set(index: Int, element: E): E = delegate.set(index, element)
}
