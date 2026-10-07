package org.jsoup.select

import com.fleeksoft.ksoup.select.Collector as KsCollector
import org.jsoup.nodes.Element
import org.jsoup.nodes.asFacadeElement

/** JVM 系 (android/jvm) actual: @JvmStatic 生成类上真静态桥, 供扩展 jar/dex invokestatic */
public actual object Collector {
    @JvmStatic
    public actual fun findFirst(evaluator: Evaluator, root: Element): Element? =
        KsCollector.findFirst(evaluator.ksoupEvaluator, root.ksoupElement)?.let { asFacadeElement(it) }
}
