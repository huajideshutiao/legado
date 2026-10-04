@file:Suppress("unused")

package org.jsoup.nodes

import com.fleeksoft.ksoup.nodes.Comment as KsComment

/** jsoup 兼容层 Comment 门面,委托底层 [KsComment] */
public open class Comment internal constructor(node: KsComment) : Node(node) {

    private val ksoupComment: KsComment
        get() = ksoupNode as KsComment

    public constructor(data: String) : this(KsComment(data))

    public fun getData(): String = ksoupComment.getData()

    public fun setData(data: String): Comment {
        ksoupComment.setData(data)
        return this
    }

    public override fun clone(): Comment = Comment(ksoupComment.clone())
}
