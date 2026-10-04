@file:Suppress("unused")

package org.jsoup.select

import com.fleeksoft.ksoup.select.Collector as KsCollector
import org.jsoup.nodes.Element
import org.jsoup.nodes.asFacadeElement

/** jsoup 兼容层 Collector 门面,委托底层 [KsCollector] */
public object Collector {

    @JvmStatic
    public fun findFirst(evaluator: Evaluator, root: Element): Element? =
        KsCollector.findFirst(evaluator.ksoupEvaluator, root.ksoupElement)?.let { asFacadeElement(it) }
}
