package org.jsoup.parser

import com.fleeksoft.ksoup.parser.Parser as KsParser

/** ios/ohos actual 共用的 Parser 门面实现 (native 无 @JvmStatic 静态桥概念) */
internal fun parserHtmlParserImpl(): Parser = Parser(KsParser.htmlParser())

internal fun parserXmlParserImpl(): Parser = Parser(KsParser.xmlParser())

internal fun parserUnescapeEntitiesImpl(html: String, inAttribute: Boolean): String =
    KsParser.unescapeEntities(html, inAttribute)
