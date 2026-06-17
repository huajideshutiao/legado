package io.legado.app.ui.book.filter

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SearchBookFilter
import kotlinx.coroutines.CoroutineScope

class SourceFilterRuleViewModel(application: Application) : BaseViewModel(application) {

    private fun mutate(block: suspend CoroutineScope.() -> Unit): Coroutine<Unit> =
        execute(block = block).onSuccess { SearchBookFilter.reload() }

    fun update(vararg rule: SourceFilterRule) = mutate {
        appDb.sourceFilterRuleDao.update(*rule)
    }

    fun delete(rule: SourceFilterRule) = mutate {
        appDb.sourceFilterRuleDao.delete(rule)
    }

    fun delSelection(rules: List<SourceFilterRule>) = mutate {
        appDb.sourceFilterRuleDao.delete(*rules.toTypedArray())
    }

    fun enableSelection(rules: List<SourceFilterRule>) = mutate {
        val array = Array(rules.size) { rules[it].copy(enabled = true) }
        appDb.sourceFilterRuleDao.update(*array)
    }

    fun disableSelection(rules: List<SourceFilterRule>) = mutate {
        val array = Array(rules.size) { rules[it].copy(enabled = false) }
        appDb.sourceFilterRuleDao.update(*array)
    }

    fun toTop(rule: SourceFilterRule) = mutate {
        rule.order = appDb.sourceFilterRuleDao.minOrder - 1
        appDb.sourceFilterRuleDao.update(rule)
    }

    fun topSelect(rules: List<SourceFilterRule>) = mutate {
        var minOrder = appDb.sourceFilterRuleDao.minOrder - rules.size
        rules.forEach { it.order = ++minOrder }
        appDb.sourceFilterRuleDao.update(*rules.toTypedArray())
    }

    fun toBottom(rule: SourceFilterRule) = mutate {
        rule.order = appDb.sourceFilterRuleDao.maxOrder + 1
        appDb.sourceFilterRuleDao.update(rule)
    }

    fun bottomSelect(rules: List<SourceFilterRule>) = mutate {
        var maxOrder = appDb.sourceFilterRuleDao.maxOrder
        rules.forEach { it.order = ++maxOrder }
        appDb.sourceFilterRuleDao.update(*rules.toTypedArray())
    }

    fun upOrder() = mutate {
        val rules = appDb.sourceFilterRuleDao.all
        for ((index, rule) in rules.withIndex()) {
            rule.order = index + 1
        }
        appDb.sourceFilterRuleDao.update(*rules.toTypedArray())
    }
}
