@file:Suppress("unused")

package org.jsoup.nodes

import com.fleeksoft.ksoup.nodes.TextNode as KsTextNode

/** jsoup 兼容层 TextNode 门面,委托底层 [KsTextNode]; CDataNode 也是 KsTextNode, 同样由此包装 */
public open class TextNode internal constructor(node: KsTextNode) : Node(node) {

    private val ksoupTextNode: KsTextNode
        get() = ksoupNode as KsTextNode

    public constructor(text: String, baseUri: String = "") : this(KsTextNode(text)) {
        if (baseUri.isNotEmpty()) setBaseUri(baseUri)
    }

    public fun text(): String = ksoupTextNode.text()

    public fun text(text: String): TextNode {
        ksoupTextNode.text(text)
        return this
    }

    public fun getWholeText(): String = ksoupTextNode.getWholeText()

    public fun isBlank(): Boolean = ksoupTextNode.isBlank()

    public fun splitText(offset: Int): TextNode =
        TextNode(ksoupTextNode.splitText(offset))

    public override fun clone(): TextNode = TextNode(ksoupTextNode.clone())
}
