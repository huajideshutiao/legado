@file:Suppress("unused")

package org.jsoup.nodes

import com.fleeksoft.ksoup.nodes.Document as KsDocument

/**
 * jsoup 兼容层 Document 门面,委托底层 [KsDocument]。
 * OutputSettings/connection/forms 等扩展 0 引用, 暂不纳入门面。
 */
public class Document internal constructor(document: KsDocument) : Element(document) {

    internal val ksoupDocument: KsDocument
        get() = ksoupNode as KsDocument

    public constructor(baseUri: String = "") : this(KsDocument(baseUri))

    public fun location(): String? = ksoupDocument.location()

    public fun head(): Element = asFacadeElement(ksoupDocument.head())

    public fun body(): Element = asFacadeElement(ksoupDocument.body())

    public fun title(): String = ksoupDocument.title()

    public fun title(title: String): Document {
        ksoupDocument.title(title)
        return this
    }

    public fun createElement(tagName: String): Element =
        asFacadeElement(ksoupDocument.createElement(tagName))

    public override fun clone(): Document =
        Document(ksoupNode.clone() as KsDocument)
}
