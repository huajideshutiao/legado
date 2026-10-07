package org.jsoup.select

import com.fleeksoft.ksoup.select.QueryParser as KsQueryParser

/** JVM 系 (android/jvm) actual: @JvmStatic 生成类上真静态桥, 供扩展 jar/dex invokestatic */
public actual object QueryParser {
    @JvmStatic
    public actual fun parse(query: String): Evaluator = Evaluator(KsQueryParser.parse(query))
}
