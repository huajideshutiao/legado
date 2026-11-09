package io.legado.app.model.webBook

import io.legado.app.utils.scan.BalanceScan

/**
 * URL 参数声明格式错误。位置是声明起始 `<` 在本次扫描字符串中的下标。
 * 调用方据此停止本次请求并把位置反馈给书源作者。
 */
class ExploreOptionSyntaxException(
    val position: Int,
    detail: String,
) : IllegalArgumentException("发现参数声明格式错误(位置 $position): $detail")

/**
 * 同名参数的共享原始选择: 只含用户选中的取值, 不含每段自己的 prefix/suffix/separator 包装。
 * 模板里同名声明共用这一份取值, 每个出现位置再按自己的包装拼出提交文本。
 */
data class ExploreOptionSelection(
    val multiSelect: Boolean,
    val values: List<String>,
)

/**
 * 参数行只读快照。页面会话以它作为定义与选择的唯一状态, UI 只读渲染。
 */
data class ExploreOptionUiState(
    val name: String,
    val items: List<Pair<String, String>>,
    val multiSelect: Boolean,
    val separator: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val selectedValue: String = "",
    val selectedValues: Set<String> = emptySet(),
) {
    /** 分组标题项: 多选里取值为空的项不可勾选, 只用于分段。 */
    val headerIndexes: Set<Int>
        get() = if (!multiSelect) emptySet()
        else items.indices.filterTo(LinkedHashSet()) { items[it].second.isBlank() }

    /** 按取值身份映射选中下标: 重复取值的别名项一起显示为选中。 */
    val selectedIndexes: Set<Int>
        get() = items.indices.filterTo(LinkedHashSet()) {
            if (multiSelect) items[it].second.isNotBlank() && items[it].second in selectedValues
            else items[it].second == selectedValue
        }

    fun sameDefinition(other: ExploreOptionUiState): Boolean =
        name == other.name && items == other.items && multiSelect == other.multiSelect &&
            separator == other.separator && prefix == other.prefix && suffix == other.suffix

    /** 选择是否按值身份相同; 多选取值集合, 提交顺序不当作选择变化。 */
    fun sameSelection(other: ExploreOptionUiState): Boolean =
        if (multiSelect && other.multiSelect) {
            selectedValues == other.selectedValues
        } else {
            multiSelect == other.multiSelect && selectedValue == other.selectedValue
        }

    fun selectionSnapshot(): ExploreOptionSelection =
        if (multiSelect) {
            if (selectedValues.isEmpty()) {
                ExploreOptionSelection(true, emptyList())
            } else {
                ExploreOptionSelection(
                    true,
                    items.asSequence()
                        .map { it.second }
                        .filter { it.isNotBlank() && it in selectedValues }
                        .distinct()
                        .toList(),
                )
            }
        } else {
            ExploreOptionSelection(
                false,
                if (selectedValue.isBlank()) emptyList() else listOf(selectedValue),
            )
        }

    /** 是否由用户选离了默认值; 删除定义时用它判断是否需要提示条件变化。 */
    fun hasExplicitSelection(): Boolean =
        if (multiSelect) selectedValues.isNotEmpty()
        else selectedValue != items.firstOrNull()?.second.orEmpty()
}

/**
 * 一次定义更新的结果: 新定义列表 + 旧选择是否在新定义下失效。
 */
data class ExploreOptionDefinitionUpdate(
    val options: List<ExploreOptionUiState>,
    val selectionInvalidated: Boolean,
)

/** 本次声明下旧选择已失效, 请求层在发 HTTP 前停止。 */
class ExploreOptionSelectionInvalidatedException :
    IllegalStateException("发现参数选择已失效，请刷新或重新选择")

/** 本次真实声明 + 有效原始选择 (已按声明校验)。 */
data class ExploreOptionResolution(
    val options: List<ExploreOption>,
    val selections: Map<String, ExploreOptionSelection>,
    val selectionInvalidated: Boolean,
)

/**
 * 用当前声明校验调用方给出的原始选择: 有效值沿用, 多选取交集, 单选失效/模式变化回落默认值。
 * 只使用本次真实解析出的声明, 不读原始模板。
 */
fun resolveExploreOptionSelections(
    parsed: List<ExploreOption>,
    provided: Map<String, ExploreOptionSelection>?,
): ExploreOptionResolution {
    var invalidated = false
    val selections = LinkedHashMap<String, ExploreOptionSelection>(parsed.size)
    parsed.forEach { option ->
        val given = provided?.get(option.name)
        val effective = when {
            given == null -> option.selectionSnapshot()
            given.multiSelect != option.multiSelect -> {
                invalidated = true
                option.selectionSnapshot()
            }

            option.multiSelect -> {
                val wanted = given.values.distinct()
                val allowed = option.options.asSequence()
                    .map { it.second }
                    .filter { it.isNotBlank() }
                    .toHashSet()
                val kept = wanted.filter { it in allowed }
                if (kept.size != wanted.size) invalidated = true
                ExploreOptionSelection(true, kept)
            }

            else -> {
                val wanted = given.values.distinct()
                val value = wanted.firstOrNull()
                val valid = when {
                    wanted.size > 1 -> false
                    value == null -> option.options.any { it.second.isBlank() }
                    else -> option.options.any { it.second == value }
                }
                if (!valid) invalidated = true
                val fallback = option.options.firstOrNull()?.second.orEmpty()
                val chosen = if (valid) value else fallback
                ExploreOptionSelection(
                    false,
                    if (chosen.isNullOrEmpty()) emptyList() else listOf(chosen),
                )
            }
        }
        option.selectedValue = if (option.multiSelect) "" else effective.values.firstOrNull().orEmpty()
        option.selectedValues.clear()
        if (option.multiSelect) option.selectedValues.addAll(effective.values)
        selections[option.name] = effective
    }
    return ExploreOptionResolution(parsed, selections, invalidated)
}

/**
 * 把本次请求解析出的声明合入页面会话定义。
 *
 * [parsed] 已由请求层按本次声明裁决出有效原始选择。
 * [replaceAll] = 本次请求用的是主模板: 解析结果就是完整定义集; 上一页链接只覆盖出现的同名项。
 */
fun mergeExploreOptionDefinitions(
    previous: List<ExploreOptionUiState>,
    parsed: List<ExploreOption>,
    replaceAll: Boolean,
): ExploreOptionDefinitionUpdate {
    val parsedStates = parsed.map { it.toUiState() }
    val previousByName = previous.associateBy { it.name }
    var selectionInvalidated = false

    fun pick(old: ExploreOptionUiState, new: ExploreOptionUiState): ExploreOptionUiState {
        if (old.sameDefinition(new) && old.sameSelection(new)) return old
        if (!old.sameSelection(new)) selectionInvalidated = true
        return new
    }

    val merged = if (replaceAll) {
        val parsedNames = parsedStates.mapTo(HashSet(parsedStates.size)) { it.name }
        parsedStates.map { new ->
            previousByName[new.name]?.let { old -> pick(old, new) } ?: new
        }.also {
            previous.forEach { old ->
                if (old.name !in parsedNames && old.hasExplicitSelection()) {
                    selectionInvalidated = true
                }
            }
        }
    } else {
        val parsedByName = parsedStates.associateBy { it.name }
        previous.map { old ->
            parsedByName[old.name]?.let { new -> pick(old, new) } ?: old
        } + parsedStates.filter { it.name !in previousByName }
    }
    return ExploreOptionDefinitionUpdate(merged, selectionInvalidated)
}

data class ExploreOption(
    val name: String,
    val options: List<Pair<String, String>>,
    val multiSelect: Boolean = false,
    val separator: String = "",
    val prefix: String = "",
    val suffix: String = "",
    var selectedValue: String = ""
) {
    /** 多选按取值身份保存选中集合; 同名取值的别名项共用这一份选择。 */
    val selectedValues: MutableSet<String> = mutableSetOf()

    /**
     * 本次要提交的原始取值: 多选按声明顺序去重拼接, 空取值项不参与; 单选取当前值。
     * 空选择直接返回空串, 不做任何包装。
     */
    val rawValue: String
        get() {
            if (!multiSelect) return selectedValue
            if (selectedValues.isEmpty()) return ""
            return options.asSequence()
                .map { it.second }
                .filter { it.isNotBlank() && it in selectedValues }
                .distinct()
                .joinToString(separator)
        }

    /** 原始取值加上本段自己的前缀/后缀; 空取值整段省略, 前后缀也不拼。 */
    val resolvedValue: String
        get() {
            val core = rawValue
            if (core.isBlank()) return ""
            return prefix + core + suffix
        }

    fun selectionSnapshot(): ExploreOptionSelection =
        ExploreOptionSelection(
            multiSelect = multiSelect,
            values = if (multiSelect) {
                if (selectedValues.isEmpty()) emptyList()
                else options.asSequence()
                    .map { it.second }
                    .filter { it.isNotBlank() && it in selectedValues }
                    .distinct()
                    .toList()
            } else {
                if (selectedValue.isBlank()) emptyList() else listOf(selectedValue)
            },
        )

    fun toUiState(): ExploreOptionUiState = ExploreOptionUiState(
        name = name,
        items = options,
        multiSelect = multiSelect,
        separator = separator,
        prefix = prefix,
        suffix = suffix,
        selectedValue = selectedValue,
        selectedValues = selectedValues.toSet(),
    )

    /** 重置到默认值。返回是否实际发生变化。 */
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

    /** 单选换值; 返回是否实际变化。 */
    fun select(value: String): Boolean {
        if (selectedValue == value) return false
        selectedValue = value
        return true
    }

    /** 多选整份换值; 只接受本声明里存在且非空的取值。返回是否实际变化。 */
    fun selectValues(values: Set<String>): Boolean {
        val allowed = options.mapTo(HashSet(options.size)) { it.second }
        val accepted = values.filterTo(HashSet()) { it.isNotBlank() && it in allowed }
        if (accepted == selectedValues) return false
        selectedValues.clear()
        selectedValues.addAll(accepted)
        return true
    }

    /**
     * 从另一份同名声明沿用选择: 模式相同才沿用, 多选取交集, 单选失效回落本声明默认值。
     * 返回是否发生了需要调用方发布的变化。
     */
    fun adoptSelectionFrom(other: ExploreOption): Boolean {
        if (multiSelect != other.multiSelect) {
            resetToDefault()
            return true
        }
        if (multiSelect) {
            return selectValues(other.selectedValues)
        }
        val value = other.selectedValue
        val valid = options.any { it.second == value }
        val target = if (valid) value else options.firstOrNull()?.second.orEmpty()
        return select(target)
    }

    fun copySelectionFrom(other: ExploreOption) {
        adoptSelectionFrom(other)
    }
}

/**
 * 解析 URL 中 `<name(key1:value1,key2:value2,...)>` 或 `<name(...)|sep>` 形式的可选项声明。
 * 首/末项若不含 `:` 则视为 prefix/suffix，resolvedValue 仅在有选中时才把它们拼上，否则整段为空。
 * 同名键以第一次出现为准；中间无 `:` 时键值取同一字符串；没有可选项时按声明位置报错。
 * 末尾 `|sep` 表示多选，多个选中值用 `sep` 拼接；不存在 `|sep` 即单选。
 * 同名声明必须给出相同的可选项集合与选择模式，允许每段各有自己的前后缀与分隔符。
 *
 * 扫描走共用件 [BalanceScan]：content 段用括号深度定位收尾 `)`，tag label 里的成对括号
 * (例如 `动作(热血):action`) 才能被正确切出。
 *
 * 确属声明却无法完整解析时抛 [ExploreOptionSyntaxException] 并给出起始位置，调用方停止本次请求；
 * 不含 `<name(` 形态的普通文本不报错。
 */
fun parseExploreOptionsFromUrl(url: String): List<ExploreOption> {
    val out = ArrayList<ExploreOption>()
    val seen = HashMap<String, ExploreOption>()
    scanExploreOptions(url) { start, _, option ->
        val existing = seen[option.name]
        when {
            existing == null -> {
                seen[option.name] = option
                out.add(option)
            }

            !existing.hasSameChoices(option) -> throw ExploreOptionSyntaxException(
                start,
                "同名参数「${option.name}」的可选项或单选/多选模式不一致",
            )
        }
    }
    return out
}

/** 一次扫描得到的声明段; 调用方可复用同一组边界做替换, 不再从每个 `<` 重扫全文。 */
data class ExploreOptionSegment(
    val start: Int,
    val endExclusive: Int,
    val option: ExploreOption,
)

/**
 * 一次递进扫描: 收集所有声明段并校验同名定义。AnalyzeUrlCore 用同一组 segments
 * 先解析选择、再替换 URL，避免 parse + replace 各扫一遍的二次增长。
 */
fun scanExploreOptionsSegments(url: String): List<ExploreOptionSegment> {
    val out = ArrayList<ExploreOptionSegment>()
    val seen = HashMap<String, ExploreOption>()
    scanExploreOptions(url) { start, endExclusive, option ->
        checkSharedDefinition(seen, option, start)
        out.add(ExploreOptionSegment(start, endExclusive, option))
    }
    return out
}

/** 用已扫描的声明段替换; 段边界只来自本次扫描。 */
fun replaceExploreOptionsWithSelection(
    url: String,
    segments: List<ExploreOptionSegment>,
    selectionOf: (name: String) -> ExploreOptionSelection?,
): String {
    if (segments.isEmpty()) return url
    val sb = StringBuilder(url.length)
    var cursor = 0
    segments.forEach { segment ->
        sb.append(url, cursor, segment.start)
        sb.append(segment.option.wrap(selectionOf(segment.option.name)))
        cursor = segment.endExclusive
    }
    sb.append(url, cursor, url.length)
    return sb.toString()
}

/**
 * 用 `selectedValue(name)` 提供的最终文本替换 url 中的 `<name(...)>` 段。
 * 回调返回的文本原样写入，不再套本段前后缀；返回 null 时回退到该段默认选择。
 */
fun replaceExploreOptionsInUrl(
    url: String,
    selectedValue: (name: String) -> String?
): String {
    var sb: StringBuilder? = null
    var cursor = 0
    val seen = HashMap<String, ExploreOption>()
    scanExploreOptions(url) { start, endExclusive, option ->
        checkSharedDefinition(seen, option, start)
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
 * 用同名共享的原始选择替换 url 中的声明段：每段按自己的 prefix/suffix/separator 包装，
 * 同名取值的别名项只提交一次，空选择整段省略。缺省选择沿用该段声明的默认值。
 */
fun replaceExploreOptionsWithSelection(
    url: String,
    selectionOf: (name: String) -> ExploreOptionSelection?
): String {
    var sb: StringBuilder? = null
    var cursor = 0
    val seen = HashMap<String, ExploreOption>()
    scanExploreOptions(url) { start, endExclusive, option ->
        checkSharedDefinition(seen, option, start)
        val buf = sb ?: StringBuilder(url.length).also { sb = it }
        buf.append(url, cursor, start)
        buf.append(option.wrap(selectionOf(option.name)))
        cursor = endExclusive
    }
    val buf = sb ?: return url
    buf.append(url, cursor, url.length)
    return buf.toString()
}

private fun ExploreOption.hasSameChoices(other: ExploreOption): Boolean =
    multiSelect == other.multiSelect && options == other.options

private fun checkSharedDefinition(
    seen: MutableMap<String, ExploreOption>,
    option: ExploreOption,
    start: Int,
) {
    val existing = seen[option.name]
    if (existing == null) {
        seen[option.name] = option
    } else if (!existing.hasSameChoices(option)) {
        throw ExploreOptionSyntaxException(
            start,
            "同名参数「${option.name}」的可选项或单选/多选模式不一致",
        )
    }
}

private fun ExploreOption.wrap(selection: ExploreOptionSelection?): String {
    val core = if (selection == null || selection.multiSelect != multiSelect) {
        if (multiSelect) "" else options.firstOrNull()?.second.orEmpty()
    } else if (multiSelect) {
        if (selection.values.isEmpty()) {
            ""
        } else {
            val wanted = selection.values.toHashSet()
            options.asSequence()
                .map { it.second }
                .filter { it.isNotBlank() && it in wanted }
                .distinct()
                .joinToString(separator)
        }
    } else {
        selection.values.firstOrNull().orEmpty()
    }
    if (core.isBlank()) return ""
    return prefix + core + suffix
}

/**
 * 单次遍历扫描 url 中所有合法 `<name(...)>` / `<name(...)|sep>` 段。
 * 命中一段回调 (start=`<` 下标, endExclusive=`>` 后一位, 已构造好的 option)。
 * 不含 `<name(` 形态的文本跳过；确属声明但解析失败的位置直接抛诊断。
 */
private inline fun scanExploreOptions(
    url: String,
    onSegment: (start: Int, endExclusive: Int, option: ExploreOption) -> Unit
) {
    val n = url.length
    var i = 0
    // 记住下一个模板起点, 每段后只从新位置向后找一次, 不重扫剩余全文。
    var templateStart = url.indexOf("{{", 0)
    while (i < n) {
        val lt = url.indexOf('<', i)
        if (templateStart in 0 until lt) {
            val templateEnd = url.indexOf("}}", templateStart + 2)
            if (templateEnd < 0) return
            i = templateEnd + 2
            if (templateStart < i) templateStart = url.indexOf("{{", i)
            continue
        }
        if (lt < 0) return
        val end = tryParseSegment(url, lt, onSegment)
        i = if (end > lt) end else lt + 1
        if (templateStart in 0 until i) templateStart = url.indexOf("{{", i)
    }
}

/**
 * 从 `<` 起点尝试解析一段。成功回调 [onSegment] 并返回 `>` 之后一位；
 * 不是声明形态返回 -1；确属声明但格式错误抛 [ExploreOptionSyntaxException]。
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
    val afterContent = BalanceScan.chomp(
        url, nameEnd, '(', ')',
        quote = false, escape = BalanceScan.Escape.NEVER
    )
    if (afterContent < 0) {
        throw ExploreOptionSyntaxException(ltIndex, "括号没有配对的右括号")
    }
    val contentStart = nameEnd + 1
    val contentEnd = afterContent - 1
    if (contentEnd == contentStart) {
        throw ExploreOptionSyntaxException(ltIndex, "括号内容为空")
    }
    p = afterContent
    var separator = ""
    if (p < n && url[p] == '|') {
        val sepStart = p + 1
        val gt = url.indexOf('>', sepStart)
        if (gt < 0) throw ExploreOptionSyntaxException(ltIndex, "多选分隔符后缺少右尖括号")
        if (gt == sepStart) throw ExploreOptionSyntaxException(ltIndex, "多选分隔符为空")
        separator = url.substring(sepStart, gt)
        p = gt
    }
    if (p >= n || url[p] != '>') {
        throw ExploreOptionSyntaxException(ltIndex, "声明缺少右尖括号")
    }
    val option = buildExploreOption(
        url = url,
        nameStart = nameStart,
        nameEnd = nameEnd,
        contentStart = contentStart,
        contentEnd = contentEnd,
        separator = separator
    ) ?: throw ExploreOptionSyntaxException(ltIndex, "没有可提交的选项")
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
    val tokens = ArrayList<String>()
    BalanceScan.splitTopLevel(url, contentStart, contentEnd, ',') { start, endExclusive ->
        tokens.add(url.substring(start, endExclusive).trim())
    }

    val prefix = if (tokens.firstOrNull()?.let { it.isNotEmpty() && ':' !in it } == true) {
        tokens.removeAt(0)
    } else ""
    val suffix = if (tokens.lastOrNull()?.let { it.isNotEmpty() && ':' !in it } == true) {
        tokens.removeAt(tokens.size - 1)
    } else ""

    val pairs = ArrayList<Pair<String, String>>(tokens.size)
    for (token in tokens) {
        if (token.isEmpty()) continue
        val colon = BalanceScan.indexOfTopLevel(token, 0, token.length, ':')
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
