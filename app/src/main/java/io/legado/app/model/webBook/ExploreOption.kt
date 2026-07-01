package io.legado.app.model.webBook

data class ExploreOption(
    val name: String,
    val options: List<Pair<String, String>>,
    val multiSelect: Boolean = false,
    val separator: String = "",
    val prefix: String = "",
    val suffix: String = "",
    var selectedValue: String = ""
) {
    val selectedValues: MutableSet<String> = mutableSetOf()

    val resolvedValue: String
        get() {
            val core = if (multiSelect) {
                if (selectedValues.isEmpty()) return ""
                options.asSequence()
                    .map { it.second }
                    .filter { it in selectedValues }
                    .joinToString(separator)
            } else {
                if (selectedValue.isEmpty()) return ""
                selectedValue
            }
            return prefix + core + suffix
        }

    /**
     * 重置到默认值。返回是否实际发生了变化(已是默认状态时返回 false)。
     *
     * 调用方据此跳过无意义的回调:重置按钮被反复点击时,避免每次都触发外层刷新。
     */
    fun resetToDefault(): Boolean {
        return if (multiSelect) {
            if (selectedValues.isEmpty()) return false
            selectedValues.clear()
            true
        } else {
            val defaultValue = options.firstOrNull()?.second.orEmpty()
            if (selectedValue == defaultValue) return false
            selectedValue = defaultValue
            true
        }
    }

    fun copySelectionFrom(other: ExploreOption) {
        if (multiSelect) {
            selectedValues.clear()
            selectedValues.addAll(other.selectedValues)
        } else {
            selectedValue = other.selectedValue
        }
    }
}

/**
 * 解析 URL 中 `<name(key1:value1,key2:value2,...)>` 或 `<name(...)|sep>` 形式的可选项声明。
 * 首/末项若不含 `:` 则视为 prefix/suffix，resolvedValue 仅在有选中时才把它们拼上，否则整段为空。
 * 同名键以第一次出现为准；中间无 `:` 时键值取同一字符串；剩余可选项为空则跳过整段。
 * 末尾 `|sep` 表示多选，多个选中值用 `sep` 拼接；不存在 `|sep` 即单选。
 *
 * 走手写扫描器: content 段用括号深度定位收尾 `)`, tag label 里的成对括号
 * (例如 `动作(热血):action`) 才能被正确切出;顺带绕开 Regex.findAll 的 Matcher/MatchResult 分配。
 */
fun parseExploreOptionsFromUrl(url: String): List<ExploreOption> {
    val out = mutableListOf<ExploreOption>()
    scanExploreOptions(url) { _, _, option ->
        if (out.none { it.name == option.name }) out.add(option)
    }
    return out
}

/**
 * 用 `selectedValue(name)` 提供的当前选择值替换 url 中的 `<name(...)>` 段。
 * 返回 null 表示该 name 没有显式选择，则回退到解析得到的默认 resolvedValue。
 */
internal fun replaceExploreOptionsInUrl(
    url: String,
    selectedValue: (name: String) -> String?
): String {
    var sb: StringBuilder? = null
    var cursor = 0
    scanExploreOptions(url) { start, endExclusive, option ->
        val buf = sb ?: StringBuilder(url.length).also { sb = it }
        buf.append(url, cursor, start)
        buf.append(selectedValue(option.name) ?: option.resolvedValue)
        cursor = endExclusive
    }
    val buf = sb ?: return url
    buf.append(url, cursor, url.length)
    return buf.toString()
}

/**
 * 单次遍历扫描 url 中所有合法 `<name(...)>` / `<name(...)|sep>` 段。
 * 命中一段回调 (start=`<` 下标, endExclusive=`>` 后一位, 已构造好的 option)。
 * 段解析失败就从下一个字符继续找 `<`,不吞进去。
 */
private inline fun scanExploreOptions(
    url: String,
    onSegment: (start: Int, endExclusive: Int, option: ExploreOption) -> Unit
) {
    val n = url.length
    var i = 0
    while (i < n) {
        val lt = url.indexOf('<', i)
        if (lt < 0) return
        val end = tryParseSegment(url, lt, onSegment)
        i = if (end > lt) end else lt + 1
    }
}

/**
 * 从 `<` 起点尝试解析一段。成功回调 [onSegment] 并返回 `>` 之后一位;失败返回 -1。
 */
private inline fun tryParseSegment(
    url: String,
    ltIndex: Int,
    onSegment: (start: Int, endExclusive: Int, option: ExploreOption) -> Unit
): Int {
    val n = url.length
    var p = ltIndex + 1
    val nameStart = p
    while (p < n) {
        val c = url[p]
        if (c == '(' || c == ')' || c == '<' || c == '>' || c.isWhitespace()) break
        p++
    }
    if (p == nameStart || p >= n || url[p] != '(') return -1
    val nameEnd = p
    p++
    val contentStart = p
    var depth = 1
    while (p < n) {
        when (url[p]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) break
            }
        }
        p++
    }
    if (depth != 0) return -1
    val contentEnd = p
    if (contentEnd == contentStart) return -1
    p++
    var separator = ""
    if (p < n && url[p] == '|') {
        val sepStart = p + 1
        val gt = url.indexOf('>', sepStart)
        if (gt < 0 || gt == sepStart) return -1
        separator = url.substring(sepStart, gt)
        p = gt
    }
    if (p >= n || url[p] != '>') return -1
    val option = buildExploreOption(
        url = url,
        nameStart = nameStart,
        nameEnd = nameEnd,
        contentStart = contentStart,
        contentEnd = contentEnd,
        separator = separator
    ) ?: return -1
    val endExclusive = p + 1
    onSegment(ltIndex, endExclusive, option)
    return endExclusive
}

private fun buildExploreOption(
    url: String,
    nameStart: Int,
    nameEnd: Int,
    contentStart: Int,
    contentEnd: Int,
    separator: String
): ExploreOption? {
    // 深度平衡切逗号:tag label 里的 `(a,b)` 不能被拆开
    val tokens = ArrayList<String>()
    var tokenStart = contentStart
    var depth = 0
    var i = contentStart
    while (i < contentEnd) {
        when (url[i]) {
            '(' -> depth++
            ')' -> depth--
            ',' -> if (depth == 0) {
                tokens.add(url.substring(tokenStart, i).trim())
                tokenStart = i + 1
            }
        }
        i++
    }
    tokens.add(url.substring(tokenStart, contentEnd).trim())

    val prefix = if (tokens.firstOrNull()?.let { it.isNotEmpty() && ':' !in it } == true) {
        tokens.removeAt(0)
    } else ""
    val suffix = if (tokens.lastOrNull()?.let { it.isNotEmpty() && ':' !in it } == true) {
        tokens.removeAt(tokens.size - 1)
    } else ""

    val pairs = ArrayList<Pair<String, String>>(tokens.size)
    for (token in tokens) {
        if (token.isEmpty()) continue
        val colon = firstColonAtDepth0(token)
        if (colon < 0) {
            pairs.add(token to token)
            continue
        }
        val first = token.substring(0, colon).trim()
        if (first.isEmpty()) continue
        val second = token.substring(colon + 1).trim()
        pairs.add(first to second)
    }
    if (pairs.isEmpty()) return null

    val name = url.substring(nameStart, nameEnd)
    val multiSelect = separator.isNotEmpty()
    return ExploreOption(
        name = name,
        options = pairs,
        multiSelect = multiSelect,
        separator = separator,
        prefix = prefix,
        suffix = suffix,
        selectedValue = if (multiSelect) "" else pairs[0].second
    )
}

/** 找 token 中第一个位于 depth 0 的 `:`,让 `label(x:y):val` 这类嵌套也能被正确切成 pair。 */
private fun firstColonAtDepth0(token: String): Int {
    var depth = 0
    for (i in token.indices) {
        when (token[i]) {
            '(' -> depth++
            ')' -> depth--
            ':' -> if (depth == 0) return i
        }
    }
    return -1
}
