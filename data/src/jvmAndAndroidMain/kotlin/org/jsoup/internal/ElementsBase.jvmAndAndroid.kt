package org.jsoup.internal

/**
 * 见 [ElementsBase]: JVM/Android 侧真继承 `java.util.ArrayList`, 与扩展编译期宿主 jsoup 的类层次一致。
 *
 * MutableList 由 supertype 带出的集合成员在此显式 override: K2 对"expect 侧由 supertype 带出、
 * actual 侧靠继承提供"的成员会判 modality 不匹配 (abstract vs open)。
 */
public actual open class ElementsBase<E> : java.util.ArrayList<E> {

    public actual constructor() : super()

    public actual constructor(initialCapacity: Int) : super(initialCapacity)

    public actual constructor(elements: Collection<E>) : super(elements)

    public actual constructor(elements: List<E>) : super(elements)

    public actual constructor(vararg elements: E) : super(elements.asList())

    actual override fun add(element: E): Boolean = super.add(element)

    actual override fun add(index: Int, element: E): Unit = super.add(index, element)

    actual override fun remove(element: E): Boolean = super.remove(element)

    actual override fun addAll(elements: Collection<E>): Boolean = super.addAll(elements)

    actual override fun addAll(index: Int, elements: Collection<E>): Boolean = super.addAll(index, elements)

    actual override fun removeAll(elements: Collection<E>): Boolean = super.removeAll(elements)

    actual override fun retainAll(elements: Collection<E>): Boolean = super.retainAll(elements)

    actual override fun clear(): Unit = super.clear()

    actual override fun set(index: Int, element: E): E = super.set(index, element)

    actual override fun removeAt(index: Int): E = super.removeAt(index)

    actual override fun listIterator(): MutableListIterator<E> = super.listIterator()

    actual override fun listIterator(index: Int): MutableListIterator<E> = super.listIterator(index)

    actual override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> = super.subList(fromIndex, toIndex)

    actual override val size: Int get() = super.size

    actual override fun isEmpty(): Boolean = super.isEmpty()

    actual override fun contains(element: E): Boolean = super.contains(element)

    actual override fun iterator(): MutableIterator<E> = super.iterator()

    actual override fun containsAll(elements: Collection<E>): Boolean = super.containsAll(elements)

    actual override fun get(index: Int): E = super.get(index)

    actual override fun indexOf(element: E): Int = super.indexOf(element)

    actual override fun lastIndexOf(element: E): Int = super.lastIndexOf(element)
}
