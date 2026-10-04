@file:Suppress("unused")

package org.jsoup.parser

import com.fleeksoft.ksoup.parser.Parser as KsParser

/**
 * jsoup 兼容层 Parser 门面,委托底层 [KsParser]。
 *
 * 扩展仅使用 htmlParser/xmlParser 工厂与 unescapeEntities 静态方法;
 * Parser 实例方法 (parseInput 等) 未纳入门面。
 */
public class Parser private constructor(internal val ksoupParser: KsParser) {

    public companion object {

        @JvmStatic
        public fun htmlParser(): Parser = Parser(KsParser.htmlParser())

        @JvmStatic
        public fun xmlParser(): Parser = Parser(KsParser.xmlParser())

        @JvmStatic
        public fun unescapeEntities(html: String, inAttribute: Boolean): String =
            KsParser.unescapeEntities(html, inAttribute)
    }
}
