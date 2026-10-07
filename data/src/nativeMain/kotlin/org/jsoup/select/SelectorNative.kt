package org.jsoup.select

import com.fleeksoft.ksoup.select.Collector as KsCollector
import com.fleeksoft.ksoup.select.QueryParser as KsQueryParser
import org.jsoup.nodes.Element
import org.jsoup.nodes.asFacadeElement

/** ios/ohos actual 共用的 select 门面实现 */
internal fun collectorFindFirstImpl(evaluator: Evaluator, root: Element): Element? =
    KsCollector.findFirst(evaluator.ksoupEvaluator, root.ksoupElement)?.let { asFacadeElement(it) }

internal fun queryParserParseImpl(query: String): Evaluator = Evaluator(KsQueryParser.parse(query))
