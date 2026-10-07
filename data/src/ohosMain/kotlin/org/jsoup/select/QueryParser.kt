package org.jsoup.select

/** ohos actual, 实现共用 nativeMain 的 impl */
public actual object QueryParser {
    public actual fun parse(query: String): Evaluator = queryParserParseImpl(query)
}
