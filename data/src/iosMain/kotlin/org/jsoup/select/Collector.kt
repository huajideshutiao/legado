package org.jsoup.select

import org.jsoup.nodes.Element

/** ios actual, 实现共用 nativeMain 的 impl */
public actual object Collector {
    public actual fun findFirst(evaluator: Evaluator, root: Element): Element? =
        collectorFindFirstImpl(evaluator, root)
}
