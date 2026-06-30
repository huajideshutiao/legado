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

private val exploreOptionRegex =
    "<([^()<> \\s]+)\\(([^()>]+)\\)(?:\\|([^>]+))?>".toRegex()

/**
 * 解析 URL 中 `<name(key1:value1,key2:value2,...)>` 或 `<name(...)|sep>` 形式的可选项声明。
 * 首/末项若不含 `:` 则视为 prefix/suffix，resolvedValue 仅在有选中时才把它们拼上，否则整段为空。
 * 同名键以第一次出现为准；中间无 `:` 时键值取同一字符串；剩余可选项为空则跳过整段。
 * 末尾 `|sep` 表示多选，多个选中值用 `sep` 拼接；不存在 `|sep` 即单选。
 */
fun parseExploreOptionsFromUrl(url: String): List<ExploreOption> {
    val options = mutableListOf<ExploreOption>()
    exploreOptionRegex.findAll(url).forEach { match ->
        val parsed = parseExploreOptionFromMatch(match) ?: return@forEach
        if (options.any { it.name == parsed.name }) return@forEach
        options.add(parsed)
    }
    return options
}

/**
 * 用 `selectedValue(name)` 提供的当前选择值替换 url 中的 `<name(...)>` 段。
 * 返回 null 表示该 name 没有显式选择，则回退到解析得到的默认 resolvedValue。
 */
internal fun replaceExploreOptionsInUrl(
    url: String,
    selectedValue: (name: String) -> String?
): String {
    return exploreOptionRegex.replace(url) { match ->
        val name = match.groupValues[1]
        selectedValue(name)
            ?: parseExploreOptionFromMatch(match)?.resolvedValue
            ?: match.value
    }
}

private fun parseExploreOptionFromMatch(match: MatchResult): ExploreOption? {
    val name = match.groupValues[1]
    val rawItems = match.groupValues[2].split(",").map { it.trim() }.toMutableList()

    val prefix = if (rawItems.firstOrNull()?.let { it.isNotEmpty() && ':' !in it } == true) {
        rawItems.removeAt(0)
    } else ""
    val suffix = if (rawItems.lastOrNull()?.let { it.isNotEmpty() && ':' !in it } == true) {
        rawItems.removeAt(rawItems.size - 1)
    } else ""

    val pairs = rawItems.mapNotNull { s ->
        val split = s.split(":", limit = 2)
        val first = split.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val second = split.getOrNull(1)?.trim() ?: first
        first to second
    }
    if (pairs.isEmpty()) return null

    val separator = match.groupValues[3]
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
