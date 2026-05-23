package io.legado.app.model.webBook

data class ExploreOption(
    val name: String,
    val options: List<Pair<String, String>>,
    var selectedValue: String
)

private val exploreOptionRegex = "<([^()<> \\s]+)\\(([^>]+)\\)>".toRegex()

/**
 * 解析 URL 中 `<name(key1:value1,key2:value2,...)>` 形式的可选项声明。
 * 同名键以第一次出现为准；缺省 `:` 时键值取同一字符串；空 `<name()>` 跳过。
 */
fun parseExploreOptionsFromUrl(url: String): List<ExploreOption> {
    val options = mutableListOf<ExploreOption>()
    exploreOptionRegex.findAll(url).forEach { match ->
        val name = match.groupValues[1]
        if (options.any { it.name == name }) return@forEach
        val pairs = match.groupValues[2].split(",").mapNotNull { s ->
            val split = s.split(":", limit = 2)
            val first = split.getOrNull(0)?.trim() ?: return@mapNotNull null
            if (first.isEmpty()) return@mapNotNull null
            val second = split.getOrNull(1)?.trim() ?: first
            first to second
        }
        if (pairs.isNotEmpty()) {
            options.add(ExploreOption(name, pairs, pairs[0].second))
        }
    }
    return options
}
