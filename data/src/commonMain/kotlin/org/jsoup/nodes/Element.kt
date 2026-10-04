@file:Suppress("unused")

package org.jsoup.nodes

import com.fleeksoft.ksoup.nodes.Element as KsElement
import org.jsoup.select.Elements
import org.jsoup.select.Evaluator

/**
 * jsoup 兼容层 Element 门面,委托底层 [KsElement]。
 *
 * 对齐 jsoup 的协变返回 (parent/attr/before/after/wrap/clone/...): 描述符必须与真实 jsoup
 * 一致, 扩展 dex 的方法引用按完整 proto 解析。
 */
public open class Element internal constructor(element: KsElement) : Node(element), Iterable<Element> {

    internal val ksoupElement: KsElement
        get() = ksoupNode as KsElement

    public final override fun nodeName(): String = ksoupElement.nodeName()

    // 对齐 jsoup 的 tag(): Tag 不提供 —— Tag 类型未纳入门面 (扩展 0 引用), 宁可缺失也不给错类型

    public fun tagName(): String = ksoupElement.tagName()

    public fun tagName(tagName: String): Element {
        ksoupElement.tagName(tagName)
        return this
    }

    public fun isBlock(): Boolean = ksoupElement.isBlock()

    public fun id(): String = ksoupElement.id()

    public fun id(id: String): Element {
        ksoupElement.id(id)
        return this
    }

    public final override fun attr(attributeKey: String, attributeValue: String): Element {
        ksoupElement.attr(attributeKey, attributeValue)
        return this
    }

    public final override fun removeAttr(attributeKey: String): Element {
        ksoupElement.removeAttr(attributeKey)
        return this
    }

    public final override fun clearAttributes(): Element {
        ksoupElement.clearAttributes()
        return this
    }

    public fun className(): String = ksoupElement.className()

    public fun classNames(): Set<String> = ksoupElement.classNames()

    public fun classNames(classNames: Set<String>): Element {
        ksoupElement.classNames(classNames)
        return this
    }

    public fun hasClass(className: String): Boolean = ksoupElement.hasClass(className)

    public fun addClass(className: String): Element {
        ksoupElement.addClass(className)
        return this
    }

    public fun removeClass(className: String): Element {
        ksoupElement.removeClass(className)
        return this
    }

    public fun toggleClass(className: String): Element {
        ksoupElement.toggleClass(className)
        return this
    }

    public fun text(): String = ksoupElement.text()

    public fun wholeText(): String = ksoupElement.wholeText()

    public fun wholeOwnText(): String = ksoupElement.wholeOwnText()

    public fun ownText(): String = ksoupElement.ownText()

    public fun hasText(): Boolean = ksoupElement.hasText()

    public fun text(text: String): Element {
        ksoupElement.text(text)
        return this
    }

    public fun html(): String = ksoupElement.html()

    public fun html(html: String): Element {
        ksoupElement.html(html)
        return this
    }

    public fun data(): String = ksoupElement.data()

    /**
     * 对齐 jsoup Element#val 语义: textarea 元素取 text(), 其余取 attr("value") (与真实 jsoup 逐字一致)。
     */
    public fun `val`(): String = ksoupElement.value()

    /** 对齐 jsoup Element#val(String) 语义: textarea 写 text(value), 其余写 value 属性, 返回自身 */
    public fun `val`(value: String): Element {
        ksoupElement.value(value)
        return this
    }

    public final override fun parent(): Element? = ksoupElement.parent()?.let { asFacadeElement(it) }

    public fun parents(): Elements = asFacadeElements(ksoupElement.parents())

    public fun children(): Elements = asFacadeElements(ksoupElement.children())

    public fun childrenSize(): Int = ksoupElement.childrenSize()

    public fun child(index: Int): Element = asFacadeElement(ksoupElement.child(index))

    public fun siblingElements(): Elements = asFacadeElements(ksoupElement.siblingElements())

    public fun nextElementSiblings(): Elements = asFacadeElements(ksoupElement.nextElementSiblings())

    public fun previousElementSiblings(): Elements =
        asFacadeElements(ksoupElement.previousElementSiblings())

    public fun nextElementSibling(): Element? =
        ksoupElement.nextElementSiblings().firstOrNull()?.let { asFacadeElement(it) }

    public fun previousElementSibling(): Element? =
        ksoupElement.previousElementSiblings().lastOrNull()?.let { asFacadeElement(it) }

    public fun firstElementSibling(): Element? =
        ksoupElement.firstElementSibling()?.let { asFacadeElement(it) }

    public fun lastElementSibling(): Element? =
        ksoupElement.lastElementSibling()?.let { asFacadeElement(it) }

    public fun firstElementChild(): Element? =
        ksoupElement.firstElementChild()?.let { asFacadeElement(it) }

    public fun lastElementChild(): Element? =
        ksoupElement.lastElementChild()?.let { asFacadeElement(it) }

    public fun elementSiblingIndex(): Int = ksoupElement.elementSiblingIndex()

    public fun textNodes(): List<TextNode> = ksoupElement.textNodes().map { TextNode(it) }

    public fun dataNodes(): List<DataNode> = ksoupElement.dataNodes().map { DataNode(it) }

    public fun select(cssQuery: String): Elements = asFacadeElements(ksoupElement.select(cssQuery))

    public fun select(evaluator: Evaluator): Elements =
        asFacadeElements(ksoupElement.select(evaluator.ksoupEvaluator))

    public fun selectFirst(cssQuery: String): Element? =
        ksoupElement.selectFirst(cssQuery)?.let { asFacadeElement(it) }

    public fun selectFirst(evaluator: Evaluator): Element? =
        ksoupElement.selectFirst(evaluator.ksoupEvaluator)?.let { asFacadeElement(it) }

    public fun expectFirst(cssQuery: String): Element =
        asFacadeElement(ksoupElement.expectFirst(cssQuery))

    public fun selectXpath(xpath: String): Elements =
        asFacadeElements(org.jsoup.select.XPathEvaluator.evaluateElements(xpath, ksoupElement))

    public fun `is`(cssQuery: String): Boolean = ksoupElement.`is`(cssQuery)

    public fun getElementById(id: String): Element? =
        ksoupElement.getElementById(id)?.let { asFacadeElement(it) }

    public fun getElementsByTag(tagName: String): Elements =
        asFacadeElements(ksoupElement.getElementsByTag(tagName))

    public fun getElementsByClass(className: String): Elements =
        asFacadeElements(ksoupElement.getElementsByClass(className))

    public fun getElementsByAttribute(key: String): Elements =
        asFacadeElements(ksoupElement.getElementsByAttribute(key))

    public fun getElementsByAttributeValue(key: String, value: String): Elements =
        asFacadeElements(ksoupElement.getElementsByAttributeValue(key, value))

    public fun getAllElements(): Elements = asFacadeElements(ksoupElement.getAllElements())

    public fun appendChild(child: Node): Element {
        ksoupElement.appendChild(child.ksoupNode)
        return this
    }

    public fun prependChild(child: Node): Element {
        ksoupElement.prependChild(child.ksoupNode)
        return this
    }

    public fun appendElement(tagName: String): Element =
        asFacadeElement(ksoupElement.appendElement(tagName))

    public fun appendText(text: String): Element {
        ksoupElement.appendText(text)
        return this
    }

    public fun append(html: String): Element {
        ksoupElement.append(html)
        return this
    }

    public fun prepend(html: String): Element {
        ksoupElement.prepend(html)
        return this
    }

    public fun empty(): Element {
        ksoupElement.empty()
        return this
    }

    public final override fun before(html: String): Element {
        ksoupElement.before(html)
        return this
    }

    public final override fun before(node: Node): Element {
        ksoupElement.before(node.ksoupNode)
        return this
    }

    public final override fun after(html: String): Element {
        ksoupElement.after(html)
        return this
    }

    public final override fun after(node: Node): Element {
        ksoupElement.after(node.ksoupNode)
        return this
    }

    public final override fun wrap(html: String): Element {
        ksoupElement.wrap(html)
        return this
    }

    /**
     * 对齐 jsoup Element#unwrap 语义 (Element 层覆写, 兼容旧版 jsoup 的描述符): DOM 效果同 [Node.unwrap],
     * 返回被上移的首个子节点按 Element 收窄, 非 Element 时为 null。
     */
    public override fun unwrap(): Element? {
        val firstChild = ksoupNode.unwrap() ?: return null
        return (firstChild as? KsElement)?.let { asFacadeElement(it) }
    }

    public final override fun root(): Element = asFacadeElement(ksoupElement.root())

    public override fun clone(): Element = asFacadeElement(ksoupElement.clone())

    public override fun iterator(): Iterator<Element> =
        ksoupElement.asSequence().map { asFacadeElement(it) }.iterator()
}
