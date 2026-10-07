package org.jsoup.internal

/**
 * [org.jsoup.select.Elements] 的存储基类。
 *
 * jvmAndAndroid 侧必须落在 `java.util.ArrayList` 上: Tachiyomi 扩展按宿主 jsoup 的
 * `Elements extends ArrayList<Element>` 层次编译, 字节码里对 Elements 的集合操作 owner 是
 * `java/util/AbstractCollection` / `java/util/AbstractList` / `java/util/ArrayList`, JVM 校验器
 * 按接收者静态类型检查可赋值性, 层次不符即拒绝加载整个扩展类。
 *
 * native 侧 stdlib 的 ArrayList 是 final 不可继承, 用 ArrayList 委托实现同一接口面
 * (native 不加载扩展 jar/dex, 无字节码层次约束)。
 *
 * MutableList 的集合成员在此逐个显式声明: K2 对"expect 侧由 supertype 带出、actual 侧靠继承提供"
 * 的成员判 modality 不匹配 (abstract vs open), 显式声明的 expect 成员才能与 actual 实现配对。
 */
public expect open class ElementsBase<E> : MutableList<E> {

    public constructor()

    public constructor(initialCapacity: Int)

    public constructor(elements: Collection<E>)

    public constructor(elements: List<E>)

    public constructor(vararg elements: E)

    public override val size: Int

    public override fun isEmpty(): Boolean

    public override fun contains(element: E): Boolean

    public override fun iterator(): MutableIterator<E>

    public override fun containsAll(elements: Collection<E>): Boolean

    public override fun get(index: Int): E

    public override fun indexOf(element: E): Int

    public override fun lastIndexOf(element: E): Int

    public override fun listIterator(): MutableListIterator<E>

    public override fun listIterator(index: Int): MutableListIterator<E>

    public override fun subList(fromIndex: Int, toIndex: Int): MutableList<E>

    public override fun add(element: E): Boolean

    public override fun add(index: Int, element: E)

    public override fun remove(element: E): Boolean

    public override fun removeAt(index: Int): E

    public override fun addAll(elements: Collection<E>): Boolean

    public override fun addAll(index: Int, elements: Collection<E>): Boolean

    public override fun removeAll(elements: Collection<E>): Boolean

    public override fun retainAll(elements: Collection<E>): Boolean

    public override fun clear()

    public override fun set(index: Int, element: E): E
}
