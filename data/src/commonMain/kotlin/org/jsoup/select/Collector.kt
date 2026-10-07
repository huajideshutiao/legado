@file:Suppress("unused")

package org.jsoup.select

import org.jsoup.nodes.Element

/** jsoup 兼容层 Collector 门面; expect/actual 分端实现 (JVM 侧挂 @JvmStatic 静态桥) */
public expect object Collector {
    public fun findFirst(evaluator: Evaluator, root: Element): Element?
}
