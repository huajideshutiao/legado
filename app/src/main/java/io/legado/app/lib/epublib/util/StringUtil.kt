package io.legado.app.lib.epublib.util

object StringUtil {
    fun collapsePathDots(path: String): String {
        val parts: MutableList<String> = path.split("/").toMutableList()
        var i = 0
        while (i < parts.size) {
            val currentDir = parts[i]
            when {
                currentDir.isEmpty() || currentDir == "." -> {
                    parts.removeAt(i)
                    i--
                }

                currentDir == ".." && i > 0 -> {
                    parts.removeAt(i)
                    parts.removeAt(i - 1)
                    i -= 2
                }
            }
            i++
        }
        return buildString {
            if (path.startsWith("/")) append('/')
            parts.forEachIndexed { idx, part ->
                append(part)
                if (idx < parts.size - 1) append('/')
            }
        }
    }

    @JvmOverloads
    fun defaultIfNull(text: String?, defaultValue: String = ""): String = text ?: defaultValue

    fun equals(text1: String?, text2: String?): Boolean = text1 == text2

    fun toString(vararg keyValues: Any?): String = buildString {
        append('[')
        var i = 0
        while (i < keyValues.size) {
            if (i > 0) append(", ")
            append(keyValues[i])
            append(": ")
            val value = if (i + 1 < keyValues.size) keyValues[i + 1] else null
            if (value == null) append("<null>") else append('\'').append(value).append('\'')
            i += 2
        }
        append(']')
    }

    fun hashCode(vararg values: String?): Int {
        var result = 31
        for (value in values) result = result xor value.toString().hashCode()
        return result
    }

    fun formatHtml(text: String): String = buildString {
        for (s in text.split("\\r?\\n".toRegex()).dropLastWhile { it.isEmpty() }) {
            val trimmed = s.trim()
            if (trimmed.isNotEmpty()) {
                if (trimmed.matches("(?i)^<img\\s([^>]+)/?>$".toRegex())) {
                    append(trimmed.replace(
                        "(?i)^<img\\s([^>]+)/?>$".toRegex(),
                        "<div class=\"duokan-image-single\"><img class=\"picture-80\" $1/></div>"
                    ))
                } else {
                    append("<p>").append(trimmed).append("</p>")
                }
            }
        }
    }
}
