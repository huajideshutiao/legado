@file:Suppress("unused")

package org.jsoup.parser

import com.fleeksoft.ksoup.parser.Parser as KsParser

/**
 * jsoup 兼容层 Parser 门面,委托底层 [KsParser]。
 *
 * 扩展仅使用 htmlParser/xmlParser 工厂与 unescapeEntities 静态方法;
 * Parser 实例方法 (parseInput 等) 未纳入门面。
 *
 * 扩展 jar/dex 按 jsoup 语义 invokestatic 这些入口, JVM 系 target 须以 @JvmStatic
 * 生成类上真静态桥; native (ios/ohos) 无此概念, 故声明 expect/actual 分端实现。
 */
public expect class Parser internal constructor(ksoupParser: KsParser) {
    internal val ksoupParser: KsParser

    public companion object {
        public fun htmlParser(): Parser

        public fun xmlParser(): Parser

        public fun unescapeEntities(html: String, inAttribute: Boolean): String
    }
}
