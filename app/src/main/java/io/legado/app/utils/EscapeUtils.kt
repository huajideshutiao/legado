package io.legado.app.utils

import org.jsoup.parser.Parser

object EscapeUtils {

    private const val FORM_FEED_CODE = 0x0C

    fun unescapeHtml(input: String): String {
        return Parser.unescapeEntities(input, false)
    }

    fun unescapeJson(input: String): String {
        if (input.indexOf('\\') < 0) return input
        val sb = StringBuilder(input.length)
        var i = 0
        val len = input.length
        while (i < len) {
            val c = input[i]
            if (c != '\\' || i + 1 >= len) {
                sb.append(c)
                i++
                continue
            }
            when (val next = input[i + 1]) {
                '\\', '"', '\'', '/' -> {
                    sb.append(next); i += 2
                }

                'n' -> {
                    sb.append('\n'); i += 2
                }

                't' -> {
                    sb.append('\t'); i += 2
                }

                'r' -> {
                    sb.append('\r'); i += 2
                }

                'b' -> {
                    sb.append('\b'); i += 2
                }

                'f' -> {
                    sb.append(Char(FORM_FEED_CODE)); i += 2
                }

                'u' -> {
                    if (i + 6 <= len) {
                        val code = input.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                        } else {
                            sb.append(c)
                            i++
                        }
                    } else {
                        sb.append(c)
                        i++
                    }
                }

                else -> {
                    sb.append(c); i++
                }
            }
        }
        return sb.toString()
    }

    fun escapeEcmaScript(input: String): String {
        val sb = StringBuilder(input.length + 16)
        for (c in input) {
            when (c) {
                '\'' -> sb.append("\\'")
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '/' -> sb.append("\\/")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> {
                    val code = c.code
                    when {
                        code == FORM_FEED_CODE -> sb.append("\\f")
                        code < 0x20 || code in 0x7F..0x9F ->
                            sb.append(String.format("\\u%04x", code))

                        else -> sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    fun jaccardSimilarity(left: CharSequence, right: CharSequence): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val leftSet = HashSet<Char>(left.length)
        for (i in 0 until left.length) leftSet.add(left[i])
        val rightSet = HashSet<Char>(right.length)
        for (i in 0 until right.length) rightSet.add(right[i])
        val intersection = leftSet.intersect(rightSet).size
        val union = leftSet.size + rightSet.size - intersection
        return intersection.toDouble() / union
    }

}
