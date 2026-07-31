package io.legado.app.utils

/**
 * 代码/JSON 自动缩进纯算法, 1:1 下沉自 app 端 `ui/widget/code/CodeView.reFormat()`。
 *
 * 原实现依赖 Editable/selection, 这里只保留字符串变换部分, 供各端编辑器复用。
 */
object CodeFormatter {

    /**
     * 格式化文本 (对照 CodeView.reFormat): 首个非空白字符是 `[` 走 JSON 美化,
     * 否则按代码重排缩进; 无法格式化时返回原文。
     */
    fun reFormat(text: String): String {
        if (text.isEmpty()) return text
        var head = 0
        while (head < text.length && text[head].isJsonWs()) head++
        val isJson = head < text.length && text[head] == '['
        return if (isJson) formatJson(text) ?: text else formatAsCode(text)
    }

    private fun formatJson(text: String): String? {
        val n = text.length
        var i = 0
        while (i < n && text[i].isJsonWs()) i++

        val sb = StringBuilder(n + 64)
        val indentUnit = "    "
        var level = 0
        var inStr = false
        var escape = false

        while (i < n) {
            val c = text[i]
            if (inStr) {
                sb.append(c)
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inStr = false
                }
                i++
                continue
            }
            when (c) {
                '"' -> {
                    sb.append(c); inStr = true; i++
                }

                '{', '[' -> {
                    val close = if (c == '{') '}' else ']'
                    var j = i + 1
                    while (j < n && text[j].isJsonWs()) j++
                    sb.append(c)
                    if (j < n && text[j] == close) {
                        sb.append(close)
                        i = j + 1
                    } else {
                        level++
                        sb.append('\n')
                        for (k in 0 until level) sb.append(indentUnit)
                        i++
                    }
                }

                '}', ']' -> {
                    if (level == 0) return null
                    level--
                    sb.append('\n')
                    for (k in 0 until level) sb.append(indentUnit)
                    sb.append(c)
                    i++
                }

                ',' -> {
                    sb.append(',').append('\n')
                    for (k in 0 until level) sb.append(indentUnit)
                    i++
                    while (i < n && text[i].isJsonWs()) i++
                }

                ':' -> {
                    sb.append(": ")
                    i++
                    while (i < n && text[i].isJsonWs()) i++
                }

                '/' -> return null // 含注释,非合法 JSON,放弃格式化
                ' ', '\t', '\n', '\r' -> i++
                else -> {
                    sb.append(c); i++
                }
            }
        }
        if (level != 0 || inStr) return null
        return sb.toString()
    }

    private fun Char.isJsonWs(): Boolean =
        this == ' ' || this == '\t' || this == '\n' || this == '\r'

    private fun formatAsCode(oldText: String): String {
        val lines = oldText.split('\n')
        val sb = StringBuilder(oldText.length + 32)
        val indentUnit = "    "
        var indentLevel = 0
        var inBlockComment = false

        for (line in lines) {
            val len = line.length
            var s = 0
            while (s < len && line[s].isWhitespace()) s++
            var e = len
            while (e > s && line[e - 1].isWhitespace()) e--
            if (s == e) {
                sb.append('\n')
                continue
            }

            // 首字符是闭括号时,本行少缩一格,但不修改累加用的 indentLevel,
            // 否则后续 delta 会在错误基准上叠加(如 `}else {` 之后的整段会少一格)。
            val first = line[s]
            val displayLevel = if (!inBlockComment &&
                (first == '}' || first == ']' || first == ')')
            ) maxOf(0, indentLevel - 1) else indentLevel
            repeat(displayLevel) { sb.append(indentUnit) }

            var delta = 0
            var stringChar = 0.toChar()
            var inLineComment = false
            var i = s
            while (i < e) {
                val c = line[i]
                when {
                    inLineComment -> {
                        sb.append(c); i++
                    }

                    inBlockComment -> {
                        sb.append(c)
                        if (c == '*' && i + 1 < e && line[i + 1] == '/') {
                            sb.append('/'); inBlockComment = false; i += 2
                        } else i++
                    }

                    stringChar.code != 0 -> {
                        sb.append(c)
                        if (c == '\\' && i + 1 < e) {
                            sb.append(line[i + 1]); i += 2
                        } else {
                            if (c == stringChar) stringChar = 0.toChar()
                            i++
                        }
                    }

                    c == '/' && i + 1 < e && line[i + 1] == '/' -> {
                        sb.append("//"); inLineComment = true; i += 2
                    }

                    c == '/' && i + 1 < e && line[i + 1] == '*' -> {
                        sb.append("/*"); inBlockComment = true; i += 2
                    }

                    c == '"' || c == '\'' || c == '`' -> {
                        sb.append(c); stringChar = c; i++
                    }

                    else -> {
                        sb.append(c)
                        when (c) {
                            '{', '[', '(' -> delta++
                            '}', ']', ')' -> delta--
                        }
                        i++
                    }
                }
            }
            sb.append('\n')
            indentLevel = maxOf(0, indentLevel + delta)
        }

        // 循环里每行末尾都 append('\n'),split 已经把原文末尾 '\n' 转成了空行,
        // 所以无论原文是否以 '\n' 结尾,这里都恒多一个,直接砍掉即可。
        sb.setLength(sb.length - 1)
        return sb.toString()
    }
}
