package io.legado.app.help.source

import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.source.SearchBookFilter.reload
import io.legado.app.utils.splitNotBlank

/**
 * 搜索 / 发现共用的结果过滤器：
 * - 任一规则命中即丢弃；
 * - 规则内部 fields 是 OR：任一字段被正则命中即视为规则命中；
 * - scope 沿用 SearchScope 字符串协议：
 *   空 → 对全部书源生效；
 *   含 `::` → 视为单源（`name::url`），按 origin 命中判断；
 *   否则视为分组 CSV，按书源所属分组与 scope 求交。
 *
 * 规则编辑保存后、书源增删改后须调 [reload]。
 */
object SearchBookFilter {

    private data class CompiledRule(
        val regex: Regex,
        val fields: Set<SourceFilterRule.Field>,
        val scope: SourceFilterRule.Scope,
    )

    private data class Snapshot(
        val rules: List<CompiledRule>,
        val originToGroups: Map<String, Set<String>>,
    ) {
        val isEmpty get() = rules.isEmpty()
    }

    @Volatile
    private var snapshot: Snapshot? = null
    private val EMPTY = Snapshot(emptyList(), emptyMap())

    fun reload() {
        snapshot = null
    }

    /**
     * 持久化规则并刷新缓存；调用方负责切到 IO 线程。
     */
    fun save(rule: SourceFilterRule, isNew: Boolean) {
        if (isNew) appDb.sourceFilterRuleDao.insert(rule)
        else appDb.sourceFilterRuleDao.update(rule)
        reload()
    }

    fun apply(books: List<SearchBook>): Pair<List<SearchBook>, Int> {
        val snap = ensure()
        if (snap.isEmpty) return books to 0
        val result = books.filterNot { it.matchesAnyRule(snap) }
        return result to (books.size - result.size)
    }

    /**
     * 列出在指定 scope 下会生效的启用规则。
     * scope 字符串协议同 [SearchScope]：空 → 全部；含 `::` → 单源；否则 → 分组 CSV。
     * 单源场景按 origin 命中；分组场景按规则范围与目标分组是否相交。
     */
    fun rulesInScope(scope: String?): List<SourceFilterRule> {
        val raw = runCatching { appDb.sourceFilterRuleDao.enabled }.getOrNull().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val target = SourceFilterRule.parseScope(scope.orEmpty())
        if (target is SourceFilterRule.Scope.None) return emptyList()
        return raw.filter { it.appliesTo(target) }
    }

    private fun SourceFilterRule.appliesTo(target: SourceFilterRule.Scope): Boolean {
        val ruleScope = SourceFilterRule.parseScope(scope)
        return when (ruleScope) {
            SourceFilterRule.Scope.All -> true
            SourceFilterRule.Scope.None -> false
            is SourceFilterRule.Scope.Source -> when (target) {
                SourceFilterRule.Scope.All -> true
                is SourceFilterRule.Scope.Source -> target.url == ruleScope.url
                else -> false
            }

            is SourceFilterRule.Scope.Groups -> when (target) {
                SourceFilterRule.Scope.All -> true
                is SourceFilterRule.Scope.Groups -> target.names.any { it in ruleScope.names }
                is SourceFilterRule.Scope.Source -> {
                    val tokens = ensure().originToGroups[target.url] ?: emptySet()
                    tokens.any { it in ruleScope.names }
                }

                else -> false
            }
        }
    }

    private fun SearchBook.matchesAnyRule(snap: Snapshot): Boolean {
        val bookGroups = snap.originToGroups[origin] ?: emptySet()
        for (rule in snap.rules) {
            if (!rule.inScope(origin, bookGroups)) continue
            if (rule.matchesAnyField(this)) return true
        }
        return false
    }

    private fun CompiledRule.inScope(origin: String, bookGroups: Set<String>): Boolean =
        when (scope) {
            SourceFilterRule.Scope.All -> true
            SourceFilterRule.Scope.None -> false
            is SourceFilterRule.Scope.Source -> origin == scope.url
            is SourceFilterRule.Scope.Groups -> bookGroups.any { it in scope.names }
        }

    private fun CompiledRule.matchesAnyField(book: SearchBook): Boolean {
        for (field in fields) {
            val text = field.extract(book) ?: continue
            if (text.isEmpty()) continue
            if (regex.containsMatchIn(text)) return true
        }
        return false
    }

    private fun SourceFilterRule.Field.extract(book: SearchBook): String? = when (this) {
        SourceFilterRule.Field.NAME -> book.name
        SourceFilterRule.Field.AUTHOR -> book.author
        SourceFilterRule.Field.INTRO -> book.intro
        SourceFilterRule.Field.KIND -> book.kind
        SourceFilterRule.Field.WORD_COUNT -> book.wordCount
    }

    @Synchronized
    private fun ensure(): Snapshot {
        snapshot?.let { return it }
        val rules = compileEnabled()
        if (rules.isEmpty()) {
            return EMPTY.also { snapshot = it }
        }
        val originToGroups = buildOriginToGroups()
        return Snapshot(rules, originToGroups).also { snapshot = it }
    }

    private fun compileEnabled(): List<CompiledRule> {
        val raw =
            runCatching { appDb.sourceFilterRuleDao.enabled }.getOrNull() ?: return emptyList()
        return raw.mapNotNull { rule ->
            if (rule.pattern.isEmpty()) return@mapNotNull null
            val regex = runCatching { Regex(rule.pattern) }.getOrNull() ?: return@mapNotNull null
            val fields = SourceFilterRule.parseFields(rule.fields)
            if (fields.isEmpty()) return@mapNotNull null
            CompiledRule(
                regex = regex,
                fields = fields,
                scope = SourceFilterRule.parseScope(rule.scope),
            )
        }
    }

    private fun buildOriginToGroups(): Map<String, Set<String>> {
        val all = runCatching { appDb.bookSourceDao.allPart }.getOrNull() ?: return emptyMap()
        val map = HashMap<String, Set<String>>(all.size)
        for (source in all) {
            val raw = source.bookSourceGroup ?: continue
            val tokens = raw.splitNotBlank(AppPattern.splitGroupRegex)
            if (tokens.isEmpty()) continue
            map[source.bookSourceUrl] = tokens.toHashSet()
        }
        return map
    }
}
