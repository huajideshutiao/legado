@file:Suppress("unused")

package org.jsoup.select

/** jsoup 兼容层 QueryParser 门面; expect/actual 分端实现 (JVM 侧挂 @JvmStatic 静态桥) */
public expect object QueryParser {
    public fun parse(query: String): Evaluator
}
