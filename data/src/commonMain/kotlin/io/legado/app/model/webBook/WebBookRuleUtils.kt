package io.legado.app.model.webBook

/**
 * webBook 共用规则解析工具。
 *
 * 原 `WebBook.parseBoolean` / `WebBook.parseRulePrefix` 是 object WebBook 的 internal 方法,
 * 抽到独立工具后可被 shared 模块的 BookList/BookReview 直接调用,
 * 同时 app 端 WebBook/BookReview/BookChapterList 仍可继续使用。
 *
 * 行为与原 WebBook 实现完全一致, 仅做位置迁移以解除 BookList/BookReview 对 WebBook 的直接依赖。
 */
object WebBookRuleUtils {

    /**
     * JS 规则求值结果 → Boolean。
     * 支持原生 Boolean / 数值 / 字符串 ("false"/"0"/"null"/空 视为 false)。
     */
    fun parseBoolean(raw: Any?): Boolean = when (raw) {
        null -> false
        is Boolean -> raw
        is Number -> raw.toDouble() != 0.0
        else -> {
            val s = raw.toString().trim()
            s.isNotEmpty() && !s.equals("false", true) && s != "0" && s != "null"
        }
    }

    /**
     * 解析规则前缀:
     * - "-" 开头: 反转列表 (reverse=true), 去掉前缀
     * - "+" 开头: 仅去掉前缀 (reverse=false)
     */
    fun parseRulePrefix(rule: String?): ParsedRule {
        var reverse = false
        var r = rule ?: ""
        if (r.startsWith("-")) {
            reverse = true
            r = r.substring(1)
        }
        if (r.startsWith("+")) {
            r = r.substring(1)
        }
        return ParsedRule(r, reverse)
    }

    data class ParsedRule(val rule: String, val reverse: Boolean)
}
