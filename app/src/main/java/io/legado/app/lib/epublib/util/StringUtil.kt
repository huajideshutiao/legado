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

    fun isNotBlank(text: String?): Boolean = !text.isNullOrBlank()

    fun isBlank(text: String?): Boolean = text.isNullOrBlank()

    fun isEmpty(text: String?): Boolean = text.isNullOrEmpty()

    fun endsWithIgnoreCase(source: String?, suffix: String?): Boolean {
        if (suffix.isNullOrEmpty()) return true
        if (source.isNullOrEmpty()) return false
        return source.endsWith(suffix, ignoreCase = true)
    }

    fun startsWithIgnoreCase(source: String?, prefix: String?): Boolean {
        if (prefix.isNullOrEmpty()) return true
        if (source.isNullOrEmpty()) return false
        return source.startsWith(prefix, ignoreCase = true)
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

    fun substringBefore(text: String?, separator: Char): String? {
        if (text.isNullOrEmpty()) return text
        val sepPos = text.indexOf(separator)
        return if (sepPos < 0) text else text.substring(0, sepPos)
    }

    fun substringBeforeLast(text: String?, separator: Char): String? {
        if (text.isNullOrEmpty()) return text
        val cPos = text.lastIndexOf(separator)
        return if (cPos < 0) text else text.substring(0, cPos)
    }

    fun substringAfterLast(text: String?, separator: Char): String? {
        if (text.isNullOrEmpty()) return text
        val cPos = text.lastIndexOf(separator)
        return if (cPos < 0) "" else text.substring(cPos + 1)
    }

    fun substringAfter(text: String?, c: Char): String? {
        if (text.isNullOrEmpty()) return text
        val cPos = text.indexOf(c)
        return if (cPos < 0) "" else text.substring(cPos + 1)
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
