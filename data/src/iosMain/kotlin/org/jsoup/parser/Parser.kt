package org.jsoup.parser

import com.fleeksoft.ksoup.parser.Parser as KsParser

/** ios actual (native 无 @JvmStatic 静态桥概念), 实现共用 nativeMain 的 impl */
public actual class Parser internal actual constructor(ksoupParser: KsParser) {
    internal actual val ksoupParser: KsParser = ksoupParser

    public actual companion object {
        public actual fun htmlParser(): Parser = parserHtmlParserImpl()

        public actual fun xmlParser(): Parser = parserXmlParserImpl()

        public actual fun unescapeEntities(html: String, inAttribute: Boolean): String =
            parserUnescapeEntitiesImpl(html, inAttribute)
    }
}
