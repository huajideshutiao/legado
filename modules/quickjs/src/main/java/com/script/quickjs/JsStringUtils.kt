package com.script.quickjs

/**
 * JS 字符串字面量转义工具。
 *
 * 统一 [QuickJsEngine]、[JsFunction]、[JsFunctionHandle] 三处重复实现,
 * 确保 \b / \f / \u0000-\u001F 等控制字符一致转义,避免注入时 JS 解析错误。
 */
internal object JsStringUtils {

    /**
     * 把 Kotlin 字符串转为 JS 字符串字面量(含首尾双引号)。
     *
     * 转义规则:
     * - \ " \n \r \t \b \f 转义为对应 JS 转义序列
     * - U+0000 ~ U+001F 转义为 \uXXXX (避免控制字符破坏 JS 解析)
     * - 其他字符原样输出
     */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
