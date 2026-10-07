package org.jsoup.parser

import com.fleeksoft.ksoup.parser.Parser as KsParser

/** JVM 系 (android/jvm) actual: @JvmStatic 生成类上真静态桥, 供扩展 jar/dex invokestatic */
public actual class Parser internal actual constructor(ksoupParser: KsParser) {
    internal actual val ksoupParser: KsParser = ksoupParser

    public actual companion object {
        @JvmStatic
        public actual fun htmlParser(): Parser = Parser(KsParser.htmlParser())

        @JvmStatic
        public actual fun xmlParser(): Parser = Parser(KsParser.xmlParser())

        @JvmStatic
        public actual fun unescapeEntities(html: String, inAttribute: Boolean): String =
            KsParser.unescapeEntities(html, inAttribute)
    }
}
