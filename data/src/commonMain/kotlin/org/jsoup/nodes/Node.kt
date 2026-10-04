@file:Suppress("unused")

package org.jsoup.nodes

import com.fleeksoft.ksoup.nodes.Comment as KsComment
import com.fleeksoft.ksoup.nodes.DataNode as KsDataNode
import com.fleeksoft.ksoup.nodes.Document as KsDocument
import com.fleeksoft.ksoup.nodes.Element as KsElement
import com.fleeksoft.ksoup.nodes.FormElement as KsFormElement
import com.fleeksoft.ksoup.nodes.Node as KsNode
import com.fleeksoft.ksoup.nodes.TextNode as KsTextNode
import org.jsoup.select.Elements

/**
 * jsoup 兼容层 Node 门面,委托底层 [KsNode]。
 *
 * 外部约束: Mihon 扩展 APK 按 org.jsoup 包名引用节点类型 (DelegateLastClassLoader 解析到宿主
 * classpath), 门面必须是 org.jsoup.nodes 下的真实类且方法描述符与 jsoup 一致 (协变返回含在内),
 * 不能用 typealias 指向 ksoup 类型替代。
 */
public open class Node internal constructor(internal val ksoupNode: KsNode) {

    public open fun nodeName(): String = ksoupNode.nodeName()

    public fun attr(attributeKey: String): String = ksoupNode.attr(attributeKey)

    public open fun attr(attributeKey: String, attributeValue: String): Node {
        ksoupNode.attr(attributeKey, attributeValue)
        return this
    }

    public fun hasAttr(attributeKey: String): Boolean = ksoupNode.hasAttr(attributeKey)

    public open fun removeAttr(attributeKey: String): Node {
        ksoupNode.removeAttr(attributeKey)
        return this
    }

    public open fun clearAttributes(): Node {
        ksoupNode.clearAttributes()
        return this
    }

    public fun absUrl(attributeKey: String): String = ksoupNode.absUrl(attributeKey)

    public fun baseUri(): String = ksoupNode.baseUri()

    public fun setBaseUri(baseUri: String) {
        ksoupNode.setBaseUri(baseUri)
    }

    public fun childNode(index: Int): Node = asFacadeNode(ksoupNode.childNode(index))

    public fun childNodes(): List<Node> = ksoupNode.childNodes().map { asFacadeNode(it) }

    public fun childNodeSize(): Int = ksoupNode.childNodeSize()

    public fun childNodesCopy(): List<Node> = ksoupNode.childNodesCopy().map { asFacadeNode(it) }

    public open fun parent(): Node? = ksoupNode.parent()?.let { asFacadeNode(it) }

    public fun ownerDocument(): Document? = ksoupNode.ownerDocument()?.let { asFacadeDocument(it) }

    public open fun root(): Node = asFacadeNode(ksoupNode.root())

    public fun remove() {
        ksoupNode.remove()
    }

    public fun replaceWith(inNode: Node) {
        ksoupNode.replaceWith(inNode.ksoupNode)
    }

    /**
     * 对齐 jsoup Node#unwrap 语义: 从 DOM 移除自身并把子节点按原位置上移到父节点, 返回被上移的首个子节点
     * (可为任意节点类型, 无子节点时为 null); 无父节点时抛 IllegalArgumentException (同 Validate.notNull)。
     */
    public open fun unwrap(): Node? {
        requireNotNull(parent()) { "Object must not be null" }
        return ksoupNode.unwrap()?.let { asFacadeNode(it) }
    }

    public open fun before(html: String): Node {
        ksoupNode.before(html)
        return this
    }

    public open fun before(node: Node): Node {
        ksoupNode.before(node.ksoupNode)
        return this
    }

    public open fun after(html: String): Node {
        ksoupNode.after(html)
        return this
    }

    public open fun after(node: Node): Node {
        ksoupNode.after(node.ksoupNode)
        return this
    }

    public open fun wrap(html: String): Node {
        ksoupNode.wrap(html)
        return this
    }

    public fun siblingNodes(): List<Node> = ksoupNode.siblingNodes().map { asFacadeNode(it) }

    public fun nextSibling(): Node? = ksoupNode.nextSibling()?.let { asFacadeNode(it) }

    public fun previousSibling(): Node? = ksoupNode.previousSibling()?.let { asFacadeNode(it) }

    public fun firstChild(): Node? = ksoupNode.firstChild()?.let { asFacadeNode(it) }

    public fun lastChild(): Node? = ksoupNode.lastChild()?.let { asFacadeNode(it) }

    public fun outerHtml(): String = ksoupNode.outerHtml()

    /** 对齐 jsoup 语义: clone 为深拷贝; 不走 Object.clone (Kotlin Any 无 clone), 由门面自行声明 */
    public open fun clone(): Node = asFacadeNode(ksoupNode.clone())

    override fun toString(): String = ksoupNode.outerHtml()
}

/** ksoup 节点转门面节点 (子类优先匹配) */
internal fun asFacadeNode(node: KsNode): Node = when (node) {
    is KsDocument -> Document(node)
    is KsFormElement -> FormElement(node)
    is KsElement -> Element(node)
    is KsTextNode -> TextNode(node)
    is KsComment -> Comment(node)
    is KsDataNode -> DataNode(node)
    // DocumentType/XmlDeclaration 等无专属门面, 按基类包装 (扩展未引用这些类型)
    else -> Node(node)
}

/** ksoup 元素转门面元素 */
internal fun asFacadeElement(element: KsElement): Element = when (element) {
    is KsDocument -> Document(element)
    is KsFormElement -> FormElement(element)
    else -> Element(element)
}

/** ksoup Document 转门面 Document */
internal fun asFacadeDocument(document: KsDocument): Document = Document(document)

/** ksoup 元素列表转门面 Elements */
internal fun asFacadeElements(elements: List<KsElement>): Elements =
    Elements().apply { elements.forEach { add(asFacadeElement(it)) } }
