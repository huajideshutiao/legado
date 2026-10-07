@file:Suppress("unused")

package org.jsoup.select

import com.fleeksoft.ksoup.select.Elements as KsElements
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.asFacadeElement
import org.jsoup.nodes.asFacadeElements

/**
 * jsoup 兼容层 Elements 门面。
 *
 * 聚合方法 (attr/text/html/...) 委托底层 [KsElements] 实现同语义。原先继承 ArrayList<Element>
 * 以对齐 jsoup 字节码, 但 native stdlib 的 ArrayList 是 final, ios/ohos 编不过; 改为
 * MutableList 接口委托, 扩展 jar/dex 的调用面走 List 接口不受影响 (类描述符不再是
 * java.util.ArrayList 子类)。
 */
public class Elements(private val delegate: MutableList<Element> = ArrayList()) :
    MutableList<Element> by delegate {

    public constructor(initialCapacity: Int) : this(ArrayList<Element>(initialCapacity))

    public constructor(elements: Collection<Element>) : this(ArrayList<Element>(elements))

    private fun toKsElements(): KsElements =
        KsElements().apply { for (element in this@Elements) add(element.ksoupElement) }

    /**
     * 对齐 jsoup Elements#select(String) 语义: 等价 Selector.select(query, roots), 跨多个根收集时按 identity
     * 去重并保持首次出现顺序 —— 列表内同时含父子元素且子元素命中时, 不会像 ArrayList.addAll 直拼那样产生重复项。
     */
    public fun select(query: String): Elements =
        asFacadeElements(toKsElements().select(query))

    /**
     * 对齐 jsoup Elements#selectFirst(String) 语义 (1.19.1+): 按列表顺序逐根查找子树内首个匹配元素
     * (命中即停), 无匹配返回 null。
     */
    public fun selectFirst(cssQuery: String): Element? =
        toKsElements().selectFirst(cssQuery)?.let { asFacadeElement(it) }

    /**
     * 对齐 jsoup Elements#expectFirst(String) 语义 (1.19.1+): 同 [selectFirst], 无匹配时抛
     * IllegalArgumentException; 消息与真实 jsoup 一致 (底层 ksoup 的 expectFirst 抛的是 ValidationException, 故自行转换)。
     */
    public fun expectFirst(cssQuery: String): Element =
        selectFirst(cssQuery)
            ?: throw IllegalArgumentException("No elements matched the query '$cssQuery' in the elements.")

    /** 对齐 jsoup Elements#not(String) 语义: 剔除匹配 query 的元素后返回新列表 (Element 无自定义 equals, identity 判等与 jsoup 一致) */
    public fun not(query: String): Elements {
        val removed = select(query)
        val result = Elements()
        for (element in this) if (element !in removed) result.add(element)
        return result
    }

    /** 对齐 jsoup Elements#is(String) 语义: 任一元素自身匹配 query 即 true (匹配范围是元素本身而非其子树) */
    public fun `is`(query: String): Boolean = toKsElements().`is`(query)

    /** 对齐 jsoup Elements#next() 语义: 取每个元素的紧邻后一个元素兄弟 (与真实 jsoup 一致, 不去重) */
    public fun next(): Elements = asFacadeElements(toKsElements().next())

    /** 对齐 jsoup Elements#next(String) 语义: 紧邻后一个元素兄弟中匹配 query 的那些 */
    public fun next(query: String): Elements = asFacadeElements(toKsElements().next(query))

    /** 对齐 jsoup Elements#nextAll() 语义: 每个元素之后的全部元素兄弟 */
    public fun nextAll(): Elements = asFacadeElements(toKsElements().nextAll())

    /** 对齐 jsoup Elements#nextAll(String) 语义: 每个元素之后匹配 query 的全部元素兄弟 */
    public fun nextAll(query: String): Elements = asFacadeElements(toKsElements().nextAll(query))

    /** 对齐 jsoup Elements#prev() 语义: 取每个元素的紧邻前一个元素兄弟 */
    public fun prev(): Elements = asFacadeElements(toKsElements().prev())

    /** 对齐 jsoup Elements#prev(String) 语义: 紧邻前一个元素兄弟中匹配 query 的那些 */
    public fun prev(query: String): Elements = asFacadeElements(toKsElements().prev(query))

    /** 对齐 jsoup Elements#prevAll() 语义: 每个元素之前的全部元素兄弟 */
    public fun prevAll(): Elements = asFacadeElements(toKsElements().prevAll())

    /** 对齐 jsoup Elements#prevAll(String) 语义: 每个元素之前匹配 query 的全部元素兄弟 */
    public fun prevAll(query: String): Elements = asFacadeElements(toKsElements().prevAll(query))

    public fun first(): Element? = this@Elements.firstOrNull()

    public fun last(): Element? = this@Elements.lastOrNull()

    /** 对齐 jsoup Elements#eq(int) 语义: 取列表第 index 个元素组成新列表, 越界返回空列表。 */
    public fun eq(index: Int): Elements {
        val result = Elements()
        if (index in indices) result.add(get(index))
        return result
    }

    public fun attr(attributeKey: String): String = toKsElements().attr(attributeKey)

    public fun attr(attributeKey: String, attributeValue: String): Elements {
        toKsElements().attr(attributeKey, attributeValue)
        return this
    }

    public fun hasAttr(attributeKey: String): Boolean = toKsElements().hasAttr(attributeKey)

    public fun removeAttr(attributeKey: String): Elements {
        toKsElements().removeAttr(attributeKey)
        return this
    }

    public fun hasClass(className: String): Boolean = toKsElements().hasClass(className)

    /**
     * 对齐 jsoup Elements#val 语义: 取首个匹配元素的表单值 —— textarea 取 text(),
     * 其余取 attr("value"); 空列表返回 ""。
     */
    public fun `val`(): String = toKsElements().value()

    /** 对齐 jsoup Elements#val(String) 语义: 对每个匹配元素设置表单值 (textarea 写 text, 其余写 value 属性) */
    public fun `val`(value: String): Elements {
        toKsElements().value(value)
        return this
    }

    public fun addClass(className: String): Elements {
        toKsElements().addClass(className)
        return this
    }

    public fun removeClass(className: String): Elements {
        toKsElements().removeClass(className)
        return this
    }

    public fun toggleClass(className: String): Elements {
        toKsElements().toggleClass(className)
        return this
    }

    public fun text(): String = toKsElements().text()

    public fun hasText(): Boolean = toKsElements().hasText()

    public fun eachText(): List<String> = toKsElements().eachText()

    public fun eachAttr(attributeKey: String): List<String> =
        toKsElements().eachAttr(attributeKey)

    public fun html(): String = toKsElements().html()

    public fun outerHtml(): String = toKsElements().outerHtml()

    public fun html(html: String): Elements {
        toKsElements().html(html)
        return this
    }

    public fun tagName(tagName: String): Elements {
        toKsElements().tagName(tagName)
        return this
    }

    public fun append(html: String): Elements {
        toKsElements().append(html)
        return this
    }

    public fun prepend(html: String): Elements {
        toKsElements().prepend(html)
        return this
    }

    public fun wrap(html: String): Elements {
        toKsElements().wrap(html)
        return this
    }

    public fun before(html: String): Elements {
        toKsElements().before(html)
        return this
    }

    public fun after(html: String): Elements {
        toKsElements().after(html)
        return this
    }

    /** 对齐 jsoup Elements#append(Node) 语义: 在每个元素末尾插入该节点的克隆 (每个目标各一份克隆, 原节点不动) */
    public fun append(node: Node): Elements {
        for (element in this) element.appendChild(node.clone())
        return this
    }

    /** 对齐 jsoup Elements#prepend(Node) 语义: 在每个元素开头插入该节点的克隆 (每个目标各一份克隆) */
    public fun prepend(node: Node): Elements {
        for (element in this) element.prependChild(node.clone())
        return this
    }

    /** 对齐 jsoup Elements#before(Node) 语义: 在每个元素外侧之前插入该节点的克隆 (每个目标各一份克隆) */
    public fun before(node: Node): Elements {
        for (element in this) element.before(node.clone())
        return this
    }

    /** 对齐 jsoup Elements#after(Node) 语义: 在每个元素外侧之后插入该节点的克隆 (每个目标各一份克隆) */
    public fun after(node: Node): Elements {
        for (element in this) element.after(node.clone())
        return this
    }

    /** 对齐 jsoup Elements#unwrap 语义: 从 DOM 移除列表内各元素并将其子节点上移到各自父节点 (保留内容丢弃外壳), 返回自身 */
    public fun unwrap(): Elements {
        for (element in this) element.unwrap()
        return this
    }

    /** 对齐 jsoup 语义: 将集合内元素从所属 DOM 中移除, 并返回自身 */
    public fun remove(): Elements {
        toKsElements().remove()
        return this
    }

    /** 对齐 jsoup Elements#deselect(int) 语义 (1.19.2+): 仅从列表移除该下标元素、不动 DOM, 返回被移除元素 */
    public fun deselect(index: Int): Element = removeAt(index)

    public fun empty(): Elements {
        toKsElements().empty()
        return this
    }

    public fun parents(): Elements = asFacadeElements(toKsElements().parents())

    public fun textNodes(): List<org.jsoup.nodes.TextNode> =
        flatMap { it.textNodes() }

    /** 对齐 jsoup Elements#comments 语义: 收集列表内各元素的直接子 Comment 节点 */
    public fun comments(): List<org.jsoup.nodes.Comment> =
        toKsElements().comments().map { org.jsoup.nodes.Comment(it) }

    /** 对齐 jsoup Elements#dataNodes 语义: 收集列表内各元素的直接子 DataNode 节点 (script/style 等的内容) */
    public fun dataNodes(): List<org.jsoup.nodes.DataNode> =
        toKsElements().dataNodes().map { org.jsoup.nodes.DataNode(it) }

    /** 对齐 jsoup Elements#forms 语义: 返回列表内的 FormElement 元素 (解析器为 <form> 生成), 无则空列表 */
    public fun forms(): List<org.jsoup.nodes.FormElement> =
        toKsElements().forms().map { org.jsoup.nodes.FormElement(it) }

    override fun toString(): String = outerHtml()
}
