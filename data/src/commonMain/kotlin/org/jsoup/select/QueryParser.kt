@file:Suppress("unused")

package org.jsoup.select

import com.fleeksoft.ksoup.select.QueryParser as KsQueryParser

/** jsoup 兼容层 QueryParser 门面,委托底层 [KsQueryParser] */
public object QueryParser {

    @JvmStatic
    public fun parse(query: String): Evaluator = Evaluator(KsQueryParser.parse(query))
}
