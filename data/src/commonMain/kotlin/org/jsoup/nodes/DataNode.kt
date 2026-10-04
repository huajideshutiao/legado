@file:Suppress("unused")

package org.jsoup.nodes

import com.fleeksoft.ksoup.nodes.DataNode as KsDataNode

/** jsoup 兼容层 DataNode 门面,委托底层 [KsDataNode] */
public open class DataNode internal constructor(node: KsDataNode) : Node(node) {

    private val ksoupDataNode: KsDataNode
        get() = ksoupNode as KsDataNode

    public constructor(data: String) : this(KsDataNode(data))

    public fun getWholeData(): String = ksoupDataNode.getWholeData()

    public fun setWholeData(data: String): DataNode {
        ksoupDataNode.setWholeData(data)
        return this
    }

    public override fun clone(): DataNode = DataNode(ksoupDataNode.clone())
}
